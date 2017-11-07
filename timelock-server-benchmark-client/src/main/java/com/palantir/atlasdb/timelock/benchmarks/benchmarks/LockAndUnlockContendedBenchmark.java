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

package com.palantir.atlasdb.timelock.benchmarks.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.immutables.value.Value;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.palantir.atlasdb.timelock.benchmarks.config.BenchmarkSettings;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.StringLockDescriptor;
import com.palantir.lock.v2.LockRequest;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.TimelockService;

public class LockAndUnlockContendedBenchmark extends AbstractBenchmark<LockAndUnlockContendedBenchmark.Settings> {
    private static final int ACQUIRE_TIMEOUT_MS = 50_000;

    @Value.Immutable
    public interface Settings extends BenchmarkSettings {

        int numDistinctLocks();

    }

    private final TimelockService timelock;
    private final List<LockDescriptor> lockDescriptors;
    private final AtomicLong counter = new AtomicLong(0);

    public static Map<String, Object> execute(SerializableTransactionManager txnManager, Settings settings) {
        return new LockAndUnlockContendedBenchmark(txnManager.getTimelockService(), settings).execute();
    }

    protected LockAndUnlockContendedBenchmark(TimelockService timelock, Settings settings) {
        super(settings);

        this.timelock = timelock;

        List<LockDescriptor> descriptors = Lists.newArrayListWithExpectedSize(settings.numDistinctLocks());
        for (int i = 0; i < settings.numDistinctLocks(); i++) {
            descriptors.add(StringLockDescriptor.of(UUID.randomUUID().toString()));
        }
        lockDescriptors = ImmutableList.copyOf(descriptors);
    }

    @Override
    protected void performOneCall() {
        LockToken token = timelock.lock(nextRequest()).getToken();
        boolean wasUnlocked = timelock.unlock(ImmutableSet.of(token)).contains(token);
        Preconditions.checkState(wasUnlocked, "unlock returned false");
    }

    private LockRequest nextRequest() {
        LockDescriptor lockDescriptor = lockDescriptors.get((int) (counter.incrementAndGet() % lockDescriptors.size()));
        return LockRequest.of(ImmutableSet.of(lockDescriptor), ACQUIRE_TIMEOUT_MS);
    }
}
