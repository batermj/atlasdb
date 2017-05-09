/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
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
package com.palantir.atlasdb.sweep;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.InsufficientConsistencyException;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.SweepResults;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.schema.generated.SweepTableFactory;
import com.palantir.atlasdb.sweep.priority.ImmutableUpdateSweepPriority;
import com.palantir.atlasdb.sweep.priority.NextTableToSweepProvider;
import com.palantir.atlasdb.sweep.priority.NextTableToSweepProviderImpl;
import com.palantir.atlasdb.sweep.priority.SweepPriorityStore;
import com.palantir.atlasdb.sweep.progress.ImmutableSweepProgress;
import com.palantir.atlasdb.sweep.progress.SweepProgress;
import com.palantir.atlasdb.sweep.progress.SweepProgressStore;
import com.palantir.atlasdb.transaction.api.LockAwareTransactionManager;
import com.palantir.atlasdb.transaction.api.TransactionManager;
import com.palantir.atlasdb.transaction.impl.TxTask;
import com.palantir.common.base.Throwables;
import com.palantir.common.time.Clock;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.LockMode;
import com.palantir.lock.LockRefreshToken;
import com.palantir.lock.LockRequest;
import com.palantir.lock.RemoteLockService;
import com.palantir.lock.StringLockDescriptor;

public final class BackgroundSweeperImpl implements BackgroundSweeper {
    private static final Logger log = LoggerFactory.getLogger(BackgroundSweeperImpl.class);
    private final TransactionManager txManager;
    private final RemoteLockService lockService;
    private final KeyValueService kvs;
    private final SweepProgressStore sweepProgressStore;
    private final SweepPriorityStore sweepPriorityStore;
    private final NextTableToSweepProvider nextTableToSweepProvider;
    private final SweepTaskRunner sweepRunner;
    private final Supplier<Boolean> isSweepEnabled;
    private final Supplier<Long> sweepPauseMillis;
    private final Supplier<Integer> sweepRowBatchSize;
    private final Supplier<Integer> sweepCellBatchSize;
    private final BackgroundSweeperPerformanceLogger sweepPerfLogger;
    private final SweepMetrics sweepMetrics;
    private final PersistentLockManager persistentLockManager;
    private final Clock wallClock;

    private volatile float batchSizeMultiplier = 1.0f;
    private Thread daemon;

    @VisibleForTesting
    BackgroundSweeperImpl(
            TransactionManager txManager,
            RemoteLockService lockService,
            KeyValueService kvs,
            SweepProgressStore sweepProgressStore,
            SweepPriorityStore sweepPriorityStore,
            NextTableToSweepProvider nextTableToSweepProvider,
            SweepTaskRunner sweepRunner,
            Supplier<Boolean> isSweepEnabled,
            Supplier<Long> sweepPauseMillis,
            Supplier<Integer> sweepBatchSize,
            Supplier<Integer> sweepCellBatchSize,
            BackgroundSweeperPerformanceLogger sweepPerfLogger,
            SweepMetrics sweepMetrics,
            PersistentLockManager persistentLockManager,
            Clock wallClock) {
        this.txManager = txManager;
        this.lockService = lockService;
        this.kvs = kvs;
        this.sweepProgressStore = sweepProgressStore;
        this.sweepPriorityStore = sweepPriorityStore;
        this.nextTableToSweepProvider = nextTableToSweepProvider;
        this.sweepRunner = sweepRunner;
        this.isSweepEnabled = isSweepEnabled;
        this.sweepPauseMillis = sweepPauseMillis;
        this.sweepRowBatchSize = sweepBatchSize;
        this.sweepCellBatchSize = sweepCellBatchSize;
        this.sweepPerfLogger = sweepPerfLogger;
        this.sweepMetrics = sweepMetrics;
        this.persistentLockManager = persistentLockManager;
        this.wallClock = wallClock;
    }

    public static BackgroundSweeperImpl create(
            LockAwareTransactionManager txManager,
            KeyValueService kvs,
            SweepTaskRunner sweepRunner,
            Supplier<Boolean> isSweepEnabled,
            Supplier<Long> sweepPauseMillis,
            Supplier<Integer> sweepBatchSize,
            Supplier<Integer> sweepCellBatchSize,
            SweepTableFactory tableFactory,
            BackgroundSweeperPerformanceLogger sweepPerfLogger,
            PersistentLockManager persistentLockManager) {
        SweepMetrics sweepMetrics = SweepMetrics.create();
        SweepProgressStore sweepProgressStore = new SweepProgressStore(kvs, tableFactory);
        SweepPriorityStore sweepPriorityStore = new SweepPriorityStore(tableFactory);
        NextTableToSweepProvider nextTableToSweepProvider = new NextTableToSweepProviderImpl(kvs, sweepPriorityStore);
        return new BackgroundSweeperImpl(
                txManager,
                txManager.getLockService(),
                kvs,
                sweepProgressStore,
                sweepPriorityStore,
                nextTableToSweepProvider,
                sweepRunner,
                isSweepEnabled,
                sweepPauseMillis,
                sweepBatchSize,
                sweepCellBatchSize,
                sweepPerfLogger,
                sweepMetrics,
                persistentLockManager,
                System::currentTimeMillis);
    }

    @Override
    public synchronized void runInBackground() {
        Preconditions.checkState(daemon == null);
        daemon = new Thread(this);
        daemon.setDaemon(true);
        daemon.setName("BackgroundSweeper");
        daemon.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down persistent lock manager");
            try {
                persistentLockManager.shutdown();
                log.info("Shutdown complete!");
            } catch (Exception e) {
                log.warn("An exception occurred while shutting down. This means that we had the backup lock out when"
                        + "the shutdown was triggered, but failed to release it. If this is the case, sweep or backup"
                        + "may fail to take out the lock in future. If this happens consistently, "
                        + "consult the following documentation on how to release the dead lock: "
                        + "https://palantir.github.io/atlasdb/html/troubleshooting/index.html#clearing-the-backup-lock",
                        e);
            }
        }));
    }

    @Override
    public void run() {
        try (SweepLocks locks = createSweepLocks()) {
            // Wait a while before starting so short lived clis don't try to sweep.
            Thread.sleep(20 * (1000 + sweepPauseMillis.get()));
            log.debug("Starting background sweeper.");
            while (true) {
                long millisToSleep = grabLocksAndRun(locks);
                Thread.sleep(millisToSleep);
            }
        } catch (InterruptedException e) {
            log.debug("Shutting down background sweeper.");
        }
    }

    // Returns milliseconds to sleep
    @VisibleForTesting
    long grabLocksAndRun(SweepLocks locks) throws InterruptedException {
        boolean sweptSuccessfully = false;
        try {
            if (isSweepEnabled.get()) {
                locks.lockOrRefresh();
                if (locks.haveLocks()) {
                    sweptSuccessfully = runOnce();
                } else {
                    log.debug("Skipping sweep because sweep is running elsewhere.");
                }
            } else {
                log.debug("Skipping sweep because it is currently disabled.");
            }
        } catch (InsufficientConsistencyException e) {
            log.warn("Could not sweep because not all nodes of the database are online.", e);
        } catch (RuntimeException e) {
            if (checkAndRepairTableDrop()) {
                log.error("The table being swept by the background sweeper was dropped, moving on...");
            } else {
                int sweepBatchSize = (int) (batchSizeMultiplier * sweepRowBatchSize.get());
                log.error("The background sweep job failed unexpectedly with a batch size of {}"
                        + ". Attempting to continue with a lower batch size...", sweepBatchSize, e);
                // Cut batch size in half, always sweep at least one row (we round down).
                batchSizeMultiplier = Math.max(batchSizeMultiplier / 2, 1.5f / sweepRowBatchSize.get());
            }
        }
        if (sweptSuccessfully) {
            batchSizeMultiplier = Math.min(1.0f, batchSizeMultiplier * 1.01f);
            return sweepPauseMillis.get();
        } else {
            return 20 * (1000 + sweepPauseMillis.get());
        }
    }

    @VisibleForTesting
    boolean runOnce() {
        Optional<TableToSweep> tableToSweep = loadProgressOrChooseNextTable();
        if (!tableToSweep.isPresent()) {
            // Don't change this log statement. It's parsed by test automation code.
            log.debug("Skipping sweep because no table has enough new writes to be worth sweeping at the moment.");
            return false;
        } else {
            runOnceForTable(tableToSweep.get());
            return true;
        }
    }

    private void runOnceForTable(TableToSweep tableToSweep) {
        int rowBatchSize = Math.max(1, (int) (sweepRowBatchSize.get() * batchSizeMultiplier));
        int cellBatchSize = sweepCellBatchSize.get();
        Stopwatch watch = Stopwatch.createStarted();
        TableReference tableRef = tableToSweep.tableRef;
        byte[] startRow = tableToSweep.progress == null ? null : tableToSweep.progress.getStartRow();
        sweepMetrics.registerMetricsIfNecessary(tableRef);
        try {
            SweepResults results = sweepRunner.run(
                    tableRef,
                    rowBatchSize,
                    cellBatchSize,
                    startRow);
            long elapsedMillis = watch.elapsed(TimeUnit.MILLISECONDS);
            log.debug("Swept {} unique cells from {} starting at {}"
                            + " and performed {} deletions in {} ms"
                            + " up to timestamp {}.",
                    results.getCellsExamined(), tableRef, startRowToHex(startRow),
                    results.getCellsDeleted(), elapsedMillis, results.getSweptTimestamp());
            sweepPerfLogger.logSweepResults(
                    SweepPerformanceResults.builder()
                            .sweepResults(results)
                            .tableName(tableRef.getQualifiedName())
                            .elapsedMillis(elapsedMillis)
                            .build());
            saveSweepResults(tableToSweep, results);
        } catch (RuntimeException e) {
            // Error logged at a higher log level above.
            log.debug("Failed to sweep {} with row batch size {} and cell batch size {} starting from row {}",
                    tableRef,
                    rowBatchSize,
                    cellBatchSize,
                    startRowToHex(startRow));
            throw e;
        }
    }

    private static String startRowToHex(@Nullable byte[] row) {
        if (row == null) {
            return "0";
        } else {
            return PtBytes.encodeHexString(row);
        }
    }

    private final class TableToSweep {
        public final TableReference tableRef;
        @Nullable public final SweepProgress progress;

        TableToSweep(TableReference tableRef, SweepProgress progress) {
            this.tableRef = tableRef;
            this.progress = progress;
        }
    }

    private Optional<TableToSweep> loadProgressOrChooseNextTable() {
        return txManager.runTaskWithRetry(tx -> {
            Optional<SweepProgress> progress = sweepProgressStore.loadProgress(tx);
            if (progress.isPresent()) {
                return Optional.of(new TableToSweep(progress.get().getTableRef(), progress.get()));
            } else {
                Optional<TableReference> nextTable = nextTableToSweepProvider.chooseNextTableToSweep(
                        tx, sweepRunner.getConservativeSweepTimestamp());
                if (nextTable.isPresent()) {
                    log.debug("Now starting to sweep {}.", nextTable);
                    return Optional.of(new TableToSweep(nextTable.get(), null));
                } else {
                    return Optional.empty();
                }
            }
        });
    }

    private void saveSweepResults(TableToSweep tableToSweep, SweepResults currentIteration) {
        long cellsDeleted = (tableToSweep.progress == null ? 0L : tableToSweep.progress.getCellsDeleted())
                                + currentIteration.getCellsDeleted();
        long cellsExamined = (tableToSweep.progress == null ? 0L : tableToSweep.progress.getCellsExamined())
                                + currentIteration.getCellsExamined();
        long minimumSweptTimestamp = tableToSweep.progress == null
                ? currentIteration.getSweptTimestamp()
                : Math.min(currentIteration.getSweptTimestamp(), tableToSweep.progress.getMinimumSweptTimestamp());
        SweepResults cumulativeResults = SweepResults.builder()
                .cellsDeleted(cellsDeleted)
                .cellsExamined(cellsExamined)
                .sweptTimestamp(minimumSweptTimestamp)
                .nextStartRow(currentIteration.getNextStartRow())
                .build();
        if (currentIteration.getNextStartRow().isPresent()) {
            saveIntermediateSweepResults(tableToSweep, cumulativeResults);
        } else {
            saveFinalSweepResults(tableToSweep, cumulativeResults);
            performInternalCompactionIfNecessary(tableToSweep.tableRef, cumulativeResults);
            log.debug("Finished sweeping {}, examined {} unique cells, deleted {} cells.",
                    tableToSweep.tableRef, cellsExamined, cellsDeleted);
            sweepProgressStore.clearProgress();
        }
    }

    private void performInternalCompactionIfNecessary(TableReference tableRef, SweepResults results) {
        if (results.getCellsDeleted() > 0) {
            Stopwatch watch = Stopwatch.createStarted();
            kvs.compactInternally(tableRef);
            long elapsedMillis = watch.elapsed(TimeUnit.MILLISECONDS);
            log.debug("Finished performing compactInternally on {} in {} ms.", tableRef, elapsedMillis);
            sweepPerfLogger.logInternalCompaction(
                    SweepCompactionPerformanceResults.builder()
                            .tableName(tableRef.getQualifiedName())
                            .cellsDeleted(results.getCellsDeleted())
                            .cellsExamined(results.getCellsExamined())
                            .elapsedMillis(elapsedMillis)
                            .build());
        }
    }

    private void saveIntermediateSweepResults(TableToSweep tableToSweep, SweepResults results) {
        Preconditions.checkArgument(results.getNextStartRow().isPresent(),
                "Next start row should be present when saving intermediate results!");
        txManager.runTaskWithRetry((TxTask) tx -> {
            if (tableToSweep.progress == null) {
                // This is the first set of results being written for this table.
                sweepPriorityStore.update(
                        tx,
                        tableToSweep.tableRef,
                        ImmutableUpdateSweepPriority.builder().newWriteCount(0L).build());
            }
            SweepProgress newProgress = ImmutableSweepProgress.builder()
                    .tableRef(tableToSweep.tableRef)
                    .cellsDeleted(results.getCellsDeleted())
                    .cellsExamined(results.getCellsExamined())
                    //noinspection OptionalGetWithoutIsPresent // covered by precondition above
                    .startRow(results.getNextStartRow().get())
                    .minimumSweptTimestamp(results.getSweptTimestamp())
                    .build();
            sweepProgressStore.saveProgress(tx, newProgress);
            return null;
        });
    }

    private void saveFinalSweepResults(TableToSweep tableToSweep, SweepResults sweepResults) {
        txManager.runTaskWithRetry((TxTask) tx -> {
            ImmutableUpdateSweepPriority.Builder update = ImmutableUpdateSweepPriority.builder()
                    .newCellsDeleted(sweepResults.getCellsDeleted())
                    .newCellsExamined(sweepResults.getCellsExamined())
                    .newLastSweepTimeMillis(wallClock.getTimeMillis())
                    .newMinimumSweptTimestamp(sweepResults.getSweptTimestamp());
            if (tableToSweep.progress == null) {
                // This is the first (and only) set of results being written for this table.
                update.newWriteCount(0L);
            }
            sweepPriorityStore.update(tx, tableToSweep.tableRef, update.build());
            return null;
        });

        sweepMetrics.recordMetrics(tableToSweep.tableRef, sweepResults);
    }

    /**
     * Check whether the table being swept was dropped. If so, stop sweeping it and move on.
     * @return Whether the table being swept was dropped
     */
    private boolean checkAndRepairTableDrop() {
        try {
            Set<TableReference> tables = kvs.getAllTableNames();
            Optional<SweepProgress> progress = txManager.runTaskReadOnly(sweepProgressStore::loadProgress);
            if (!progress.isPresent() || tables.contains(progress.get().getTableRef())) {
                return false;
            } else {
                sweepProgressStore.clearProgress();
                return true;
            }
        } catch (RuntimeException e) {
            log.error("Failed to check whether the table being swept was dropped."
                    + " Continuing under the assumption that it wasn't...", e);
            return false;
        }
    }

    @VisibleForTesting
    SweepLocks createSweepLocks() {
        return new SweepLocks();
    }

    @VisibleForTesting
    class SweepLocks implements AutoCloseable {
        private LockRefreshToken token = null;

        public void lockOrRefresh() throws InterruptedException {
            if (token != null) {
                Set<LockRefreshToken> refreshedTokens = lockService.refreshLockRefreshTokens(ImmutableList.of(token));
                if (refreshedTokens.isEmpty()) {
                    token = null;
                }
            } else {
                LockDescriptor lock = StringLockDescriptor.of("atlas sweep");
                LockRequest request = LockRequest.builder(
                        ImmutableSortedMap.of(lock, LockMode.WRITE)).doNotBlock().build();
                token = lockService.lock(LockClient.ANONYMOUS.getClientId(), request);
            }
        }

        public boolean haveLocks() {
            return token != null;
        }

        @Override
        public void close() {
            if (token != null) {
                lockService.unlock(token);
            }
        }
    }

    @Override
    public synchronized void shutdown() {
        if (daemon == null) {
            return;
        }
        log.debug("Signalling background sweeper to shut down.");
        daemon.interrupt();
        try {
            daemon.join();
            daemon = null;
        } catch (InterruptedException e) {
            throw Throwables.rewrapAndThrowUncheckedException(e);
        }
    }

}
