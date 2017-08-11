/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.factory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLSocketFactory;
import javax.ws.rs.ClientErrorException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.atlasdb.cleaner.Cleaner;
import com.palantir.atlasdb.cleaner.CleanupFollower;
import com.palantir.atlasdb.cleaner.DefaultCleanerBuilder;
import com.palantir.atlasdb.config.AtlasDbConfig;
import com.palantir.atlasdb.config.AtlasDbRuntimeConfig;
import com.palantir.atlasdb.config.LeaderConfig;
import com.palantir.atlasdb.config.ServerListConfig;
import com.palantir.atlasdb.config.SweepConfig;
import com.palantir.atlasdb.config.TimeLockClientConfig;
import com.palantir.atlasdb.config.TimestampClientConfig;
import com.palantir.atlasdb.factory.startup.TimeLockMigrator;
import com.palantir.atlasdb.factory.timestamp.DecoratedTimelockServices;
import com.palantir.atlasdb.http.AtlasDbFeignTargetFactory;
import com.palantir.atlasdb.http.UserAgents;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.NamespacedKeyValueServices;
import com.palantir.atlasdb.keyvalue.impl.ProfilingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.SweepStatsKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.TracingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.ValidatingQueryRewritingKeyValueService;
import com.palantir.atlasdb.logging.LoggingArgs;
import com.palantir.atlasdb.persistentlock.CheckAndSetExceptionMapper;
import com.palantir.atlasdb.persistentlock.KvsBackedPersistentLockService;
import com.palantir.atlasdb.persistentlock.NoOpPersistentLockService;
import com.palantir.atlasdb.persistentlock.PersistentLockService;
import com.palantir.atlasdb.schema.SweepSchema;
import com.palantir.atlasdb.schema.generated.SweepTableFactory;
import com.palantir.atlasdb.sweep.BackgroundSweeperImpl;
import com.palantir.atlasdb.sweep.BackgroundSweeperPerformanceLogger;
import com.palantir.atlasdb.sweep.CellsSweeper;
import com.palantir.atlasdb.sweep.ImmutableSweepBatchConfig;
import com.palantir.atlasdb.sweep.NoOpBackgroundSweeperPerformanceLogger;
import com.palantir.atlasdb.sweep.PersistentLockManager;
import com.palantir.atlasdb.sweep.SpecificTableSweeper;
import com.palantir.atlasdb.sweep.SweepBatchConfig;
import com.palantir.atlasdb.sweep.SweepMetrics;
import com.palantir.atlasdb.sweep.SweepTaskRunner;
import com.palantir.atlasdb.sweep.SweeperServiceImpl;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.table.description.Schemas;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManager;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManagers;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManagers;
import com.palantir.atlasdb.transaction.impl.TimelockTimestampServiceAdapter;
import com.palantir.atlasdb.transaction.impl.TransactionTables;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.TransactionServices;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.PingableLeader;
import com.palantir.leader.proxy.AwaitingLeadershipProxy;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockRequest;
import com.palantir.lock.LockServerOptions;
import com.palantir.lock.RemoteLockService;
import com.palantir.lock.SimpleTimeDuration;
import com.palantir.lock.client.LockRefreshingRemoteLockService;
import com.palantir.lock.impl.LegacyTimelockService;
import com.palantir.lock.impl.LockRefreshingTimelockService;
import com.palantir.lock.impl.LockServiceImpl;
import com.palantir.lock.v2.TimelockService;
import com.palantir.timestamp.TimestampService;
import com.palantir.timestamp.TimestampStoreInvalidator;

public class TransactionManagerBuilder {
    private static final Logger log = LoggerFactory.getLogger(TransactionManagerBuilder.class);

    public static final LockClient LOCK_CLIENT = LockClient.of("atlas instance");
    private static final int LOGGING_INTERVAL = 60;
    @VisibleForTesting
    static Consumer<Runnable> runAsync = task -> {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    };

    private AtlasDbConfig config;
    private java.util.function.Supplier<Optional<AtlasDbRuntimeConfig>> optionalRuntimeConfigSupplier;
    private Set<Schema> schemas;
    private Environment env;
    private LockServerOptions lockOptions;
    private boolean allowHiddenTableAccess;
    private String userAgent = UserAgents.DEFAULT_USER_AGENT;

    public TransactionManagerBuilder config(AtlasDbConfig atlasDbConfig) {
        config = atlasDbConfig;
        return this;
    }

    public TransactionManagerBuilder runtimeConfig(java.util.function.Supplier<Optional<AtlasDbRuntimeConfig>> orcs) {
        optionalRuntimeConfigSupplier = orcs;
        return this;
    }

    public TransactionManagerBuilder schemas(Set<Schema> schemaSet) {
        schemas = schemaSet;
        return this;
    }

    public TransactionManagerBuilder environment(Environment environment) {
        env = environment;
        return this;
    }

    public TransactionManagerBuilder lockServerOptions(LockServerOptions lockServerOptions) {
        lockOptions = lockServerOptions;
        return this;
    }

    public TransactionManagerBuilder withHiddenTableAccess(boolean hiddenTableAccess) {
        allowHiddenTableAccess = hiddenTableAccess;
        return this;
    }

    public TransactionManagerBuilder userAgent(String agent) {
        userAgent = agent;
        return this;
    }

    public SerializableTransactionManager build() {
        checkInstallConfig(config);

        AtlasDbRuntimeConfig defaultRuntime = AtlasDbRuntimeConfig.defaultRuntimeConfig();
        java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfigSupplier =
                () -> optionalRuntimeConfigSupplier.get().orElse(defaultRuntime);

        ServiceDiscoveringAtlasSupplier atlasFactory =
                new ServiceDiscoveringAtlasSupplier(config.keyValueService(), config.leader());

        KeyValueService rawKvs = atlasFactory.getKeyValueService();

        LockRequest.setDefaultLockTimeout(
                SimpleTimeDuration.of(config.getDefaultLockTimeoutSeconds(), TimeUnit.SECONDS));
        LockAndTimestampServices lockAndTimestampServices = createLockAndTimestampServices(
                config,
                () -> runtimeConfigSupplier.get().timestampClient(),
                env,
                () -> LockServiceImpl.create(lockOptions),
                atlasFactory::getTimestampService,
                atlasFactory.getTimestampStoreInvalidator(),
                userAgent);

        KeyValueService kvs = NamespacedKeyValueServices.wrapWithStaticNamespaceMappingKvs(rawKvs);
        kvs = ProfilingKeyValueService.create(kvs, config.getKvsSlowLogThresholdMillis());
        kvs = SweepStatsKeyValueService.create(kvs,
                new TimelockTimestampServiceAdapter(lockAndTimestampServices.timelock()));
        kvs = TracingKeyValueService.create(kvs);
        kvs = AtlasDbMetrics.instrument(KeyValueService.class, kvs,
                MetricRegistry.name(KeyValueService.class, userAgent));
        kvs = ValidatingQueryRewritingKeyValueService.create(kvs);

        TransactionTables.createTables(kvs);

        PersistentLockService persistentLockService = createAndRegisterPersistentLockService(kvs, env);

        TransactionService transactionService = TransactionServices.createTransactionService(kvs);
        ConflictDetectionManager conflictManager = ConflictDetectionManagers.create(kvs);
        SweepStrategyManager sweepStrategyManager = SweepStrategyManagers.createDefault(kvs);

        Set<Schema> allSchemas = ImmutableSet.<Schema>builder()
                .add(SweepSchema.INSTANCE.getLatestSchema())
                .addAll(schemas)
                .build();
        for (Schema schema : allSchemas) {
            Schemas.createTablesAndIndexes(schema, kvs);
        }

        // Prime the key value service with logging information.
        // TODO (jkong): Needs to be changed if/when we support dynamic table creation.
        LoggingArgs.hydrate(kvs.getMetadataForTables());

        CleanupFollower follower = CleanupFollower.create(schemas);

        Cleaner cleaner = new DefaultCleanerBuilder(
                kvs,
                lockAndTimestampServices.timelock(),
                ImmutableList.of(follower),
                transactionService)
                .setBackgroundScrubAggressively(config.backgroundScrubAggressively())
                .setBackgroundScrubBatchSize(config.getBackgroundScrubBatchSize())
                .setBackgroundScrubFrequencyMillis(config.getBackgroundScrubFrequencyMillis())
                .setBackgroundScrubThreads(config.getBackgroundScrubThreads())
                .setPunchIntervalMillis(config.getPunchIntervalMillis())
                .setTransactionReadTimeout(config.getTransactionReadTimeoutMillis())
                .buildCleaner();

        SerializableTransactionManager transactionManager = new SerializableTransactionManager(kvs,
                lockAndTimestampServices.timelock(),
                lockAndTimestampServices.lock(),
                transactionService,
                Suppliers.ofInstance(AtlasDbConstraintCheckingMode.FULL_CONSTRAINT_CHECKING_THROWS_EXCEPTIONS),
                conflictManager,
                sweepStrategyManager,
                cleaner,
                allowHiddenTableAccess,
                () -> runtimeConfigSupplier.get().transaction().getLockAcquireTimeoutMillis());

        PersistentLockManager persistentLockManager = new PersistentLockManager(
                persistentLockService,
                config.getSweepPersistentLockWaitMillis());
        initializeSweepEndpointAndBackgroundProcess(runtimeConfigSupplier,
                env,
                kvs,
                transactionService,
                sweepStrategyManager,
                follower,
                transactionManager,
                persistentLockManager);

        return transactionManager;
    }

    @VisibleForTesting
    static LockAndTimestampServices createLockAndTimestampServices(
            AtlasDbConfig config,
            java.util.function.Supplier<TimestampClientConfig> runtimeConfigSupplier,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        LockAndTimestampServices lockAndTimestampServices =
                createRawServices(config, env, lock, time, invalidator, userAgent);
        return withRequestBatchingTimestampService(
                runtimeConfigSupplier,
                withRefreshingLockService(lockAndTimestampServices));
    }

    static LockAndTimestampServices withRefreshingLockService(
            LockAndTimestampServices lockAndTimestampServices) {
        return ImmutableLockAndTimestampServices.builder()
                .from(lockAndTimestampServices)
                .timelock(LockRefreshingTimelockService.createDefault(lockAndTimestampServices.timelock()))
                .lock(LockRefreshingRemoteLockService.create(lockAndTimestampServices.lock()))
                .build();
    }

    private static LockAndTimestampServices withRequestBatchingTimestampService(
            java.util.function.Supplier<TimestampClientConfig> timestampClientConfigSupplier,
            LockAndTimestampServices lockAndTimestampServices) {
        TimelockService timelockServiceWithBatching =
                DecoratedTimelockServices.createTimelockServiceWithTimestampBatching(
                        lockAndTimestampServices.timelock(), timestampClientConfigSupplier);

        return ImmutableLockAndTimestampServices.builder()
                .from(lockAndTimestampServices)
                .timestamp(new TimelockTimestampServiceAdapter(timelockServiceWithBatching))
                .timelock(timelockServiceWithBatching)
                .build();
    }

    @VisibleForTesting
    static LockAndTimestampServices createRawServices(
            AtlasDbConfig config,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        if (config.leader().isPresent()) {
            return createRawLeaderServices(config.leader().get(), env, lock, time, userAgent);
        } else if (config.timestamp().isPresent() && config.lock().isPresent()) {
            return createRawRemoteServices(config, userAgent);
        } else if (config.timelock().isPresent()) {
            TimeLockClientConfig timeLockClientConfig = config.timelock().get();
            TimeLockMigrator.create(timeLockClientConfig, invalidator, userAgent).migrate();
            return createNamespacedRawRemoteServices(timeLockClientConfig, userAgent);
        } else {
            return createRawEmbeddedServices(env, lock, time, userAgent);
        }
    }

    private static LockAndTimestampServices createNamespacedRawRemoteServices(
            TimeLockClientConfig config,
            String userAgent) {
        ServerListConfig namespacedServerListConfig = config.toNamespacedServerList();
        return getLockAndTimestampServices(namespacedServerListConfig, userAgent);
    }

    private static LockAndTimestampServices getLockAndTimestampServices(
            ServerListConfig timelockServerListConfig,
            String userAgent) {
        RemoteLockService lockService = new ServiceCreator<>(RemoteLockService.class, userAgent)
                .apply(timelockServerListConfig);
        TimelockService timelockService = new ServiceCreator<>(TimelockService.class, userAgent)
                .apply(timelockServerListConfig);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(new TimelockTimestampServiceAdapter(timelockService))
                .timelock(timelockService)
                .build();
    }

    private static LockAndTimestampServices createRawLeaderServices(
            LeaderConfig leaderConfig,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            String userAgent) {
        // Create local services, that may or may not end up being registered in an environment.
        Leaders.LocalPaxosServices localPaxosServices = Leaders.createAndRegisterLocalServices(env, leaderConfig,
                userAgent);
        LeaderElectionService leader = localPaxosServices.leaderElectionService();
        RemoteLockService localLock = AwaitingLeadershipProxy.newProxyInstance(RemoteLockService.class, lock, leader);
        TimestampService localTime = AwaitingLeadershipProxy.newProxyInstance(TimestampService.class, time, leader);
        env.register(localLock);
        env.register(localTime);

        // Create remote services, that may end up calling our own local services.
        Optional<SSLSocketFactory> sslSocketFactory = ServiceCreator.createSslSocketFactory(
                leaderConfig.sslConfiguration());
        RemoteLockService remoteLock = ServiceCreator.createService(
                sslSocketFactory,
                leaderConfig.leaders(),
                RemoteLockService.class,
                userAgent);
        TimestampService remoteTime = ServiceCreator.createService(
                sslSocketFactory,
                leaderConfig.leaders(),
                TimestampService.class,
                userAgent);

        if (leaderConfig.leaders().size() == 1) {
            // Attempting to connect to ourself while processing a request can lead to deadlock if incoming request
            // volume is high, as all Jetty threads end up waiting for the timestamp server, and no threads remain to
            // actually handle the timestamp server requests. If we are the only single leader, we can avoid the
            // deadlock entirely; so use PingableLeader's getUUID() to detect this situation and eliminate the redundant
            // call.

            PingableLeader localPingableLeader = localPaxosServices.pingableLeader();
            String localServerId = localPingableLeader.getUUID();
            PingableLeader remotePingableLeader = AtlasDbFeignTargetFactory.createRsProxy(
                    sslSocketFactory,
                    Iterables.getOnlyElement(leaderConfig.leaders()),
                    PingableLeader.class,
                    userAgent);

            // Determine asynchronously whether the remote services are talking to our local services.
            CompletableFuture<Boolean> useLocalServicesFuture = new CompletableFuture<>();
            runAsync.accept(() -> {
                int logAfter = LOGGING_INTERVAL;
                while (true) {
                    try {
                        String remoteServerId = remotePingableLeader.getUUID();
                        useLocalServicesFuture.complete(localServerId.equals(remoteServerId));
                        return;
                    } catch (ClientErrorException e) {
                        useLocalServicesFuture.complete(false);
                        return;
                    } catch (Throwable e) {
                        if (--logAfter == 0) {
                            log.warn("Failed to read remote timestamp server ID", e);
                            logAfter = LOGGING_INTERVAL;
                        }
                    }
                    Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                }
            });

            // Create dynamic service proxies, that switch to talking directly to our local services if it turns out our
            // remote services are pointed at them anyway.
            RemoteLockService dynamicLockService = LocalOrRemoteProxy.newProxyInstance(
                    RemoteLockService.class, localLock, remoteLock, useLocalServicesFuture);
            TimestampService dynamicTimeService = LocalOrRemoteProxy.newProxyInstance(
                    TimestampService.class, localTime, remoteTime, useLocalServicesFuture);
            return ImmutableLockAndTimestampServices.builder()
                    .lock(dynamicLockService)
                    .timestamp(dynamicTimeService)
                    .timelock(new LegacyTimelockService(dynamicTimeService, dynamicLockService, LOCK_CLIENT))
                    .build();

        } else {
            return ImmutableLockAndTimestampServices.builder()
                    .lock(remoteLock)
                    .timestamp(remoteTime)
                    .timelock(new LegacyTimelockService(remoteTime, remoteLock, LOCK_CLIENT))
                    .build();
        }
    }

    private static LockAndTimestampServices createRawRemoteServices(AtlasDbConfig config, String userAgent) {
        RemoteLockService lockService = new ServiceCreator<>(RemoteLockService.class, userAgent)
                .apply(config.lock().get());
        TimestampService timeService = new ServiceCreator<>(TimestampService.class, userAgent)
                .apply(config.timestamp().get());

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(timeService)
                .timelock(new LegacyTimelockService(timeService, lockService, LOCK_CLIENT))
                .build();
    }

    private static LockAndTimestampServices createRawEmbeddedServices(
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            String userAgent) {
        RemoteLockService lockService = ServiceCreator.createInstrumentedService(lock.get(),
                RemoteLockService.class,
                userAgent);
        TimestampService timeService = ServiceCreator.createInstrumentedService(time.get(),
                TimestampService.class,
                userAgent);

        env.register(lockService);
        env.register(timeService);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(timeService)
                .timelock(new LegacyTimelockService(timeService, lockService, LOCK_CLIENT))
                .build();
    }

    private static void checkInstallConfig(AtlasDbConfig config) {
        if (config.getSweepBatchSize() != null
                || config.getSweepCellBatchSize() != null
                || config.getSweepReadLimit() != null
                || config.getSweepCandidateBatchHint() != null
                || config.getSweepDeleteBatchHint() != null) {
            log.error("Your configuration specifies sweep parameters on the install config. They will be ignored."
                    + " Please use the runtime config to specify them.");
        }
    }

    private static PersistentLockService createAndRegisterPersistentLockService(
            KeyValueService kvs,
            Environment env) {
        if (!kvs.supportsCheckAndSet()) {
            return new NoOpPersistentLockService();
        }

        PersistentLockService pls = KvsBackedPersistentLockService.create(kvs);
        env.register(pls);
        env.register(new CheckAndSetExceptionMapper());
        return pls;
    }

    private static void initializeSweepEndpointAndBackgroundProcess(
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfigSupplier,
            Environment env,
            KeyValueService kvs,
            TransactionService transactionService,
            SweepStrategyManager sweepStrategyManager,
            CleanupFollower follower,
            SerializableTransactionManager transactionManager,
            PersistentLockManager persistentLockManager) {
        CellsSweeper cellsSweeper = new CellsSweeper(
                transactionManager,
                kvs,
                persistentLockManager,
                ImmutableList.of(follower));
        SweepTaskRunner sweepRunner = new SweepTaskRunner(
                kvs,
                transactionManager::getUnreadableTimestamp,
                transactionManager::getImmutableTimestamp,
                transactionService,
                sweepStrategyManager,
                cellsSweeper);
        BackgroundSweeperPerformanceLogger sweepPerfLogger = new NoOpBackgroundSweeperPerformanceLogger();
        Supplier<SweepBatchConfig> sweepBatchConfig = () -> getSweepBatchConfig(runtimeConfigSupplier.get().sweep());
        SweepMetrics sweepMetrics = new SweepMetrics();

        SpecificTableSweeper specificTableSweeper = initializeSweepEndpoint(
                env,
                kvs,
                transactionManager,
                sweepRunner,
                sweepPerfLogger,
                sweepBatchConfig,
                sweepMetrics);

        BackgroundSweeperImpl backgroundSweeper = BackgroundSweeperImpl.create(
                () -> runtimeConfigSupplier.get().sweep().enabled(),
                () -> runtimeConfigSupplier.get().sweep().pauseMillis(),
                persistentLockManager,
                specificTableSweeper);

        transactionManager.registerClosingCallback(backgroundSweeper::shutdown);
        backgroundSweeper.runInBackground();
    }

    private static SpecificTableSweeper initializeSweepEndpoint(
            Environment env,
            KeyValueService kvs,
            SerializableTransactionManager transactionManager,
            SweepTaskRunner sweepRunner,
            BackgroundSweeperPerformanceLogger sweepPerfLogger,
            Supplier<SweepBatchConfig> sweepBatchConfig,
            SweepMetrics sweepMetrics) {
        SpecificTableSweeper specificTableSweeper = SpecificTableSweeper.create(
                transactionManager,
                kvs,
                sweepRunner,
                sweepBatchConfig,
                SweepTableFactory.of(),
                sweepPerfLogger,
                sweepMetrics);
        env.register(new SweeperServiceImpl(specificTableSweeper));
        return specificTableSweeper;
    }

    private static SweepBatchConfig getSweepBatchConfig(SweepConfig sweepConfig) {
        return ImmutableSweepBatchConfig.builder()
                .maxCellTsPairsToExamine(sweepConfig.readLimit())
                .candidateBatchSize(sweepConfig.candidateBatchHint())
                .deleteBatchSize(sweepConfig.deleteBatchHint())
                .build();
    }

    public interface Environment {
        void register(Object resource);
    }
}