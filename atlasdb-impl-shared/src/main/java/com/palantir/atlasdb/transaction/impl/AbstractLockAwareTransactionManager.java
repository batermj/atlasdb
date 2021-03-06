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
package com.palantir.atlasdb.transaction.impl;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.cache.TimestampCache;
import com.palantir.atlasdb.transaction.api.LockAwareTransactionTask;
import com.palantir.atlasdb.transaction.api.TransactionFailedRetriableException;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.lock.HeldLocksToken;
import com.palantir.lock.LockRequest;

public abstract class AbstractLockAwareTransactionManager extends AbstractConditionAwareTransactionManager {

    AbstractLockAwareTransactionManager(MetricsManager metricsManager, TimestampCache timestampCache) {
        super(metricsManager, timestampCache);
    }

    @Override
    public <T, E extends Exception> T runTaskWithLocksWithRetry(
            Iterable<HeldLocksToken> lockTokens,
            Supplier<LockRequest> lockSupplier,
            LockAwareTransactionTask<T, E> task) throws E, InterruptedException {
        checkOpen();
        Supplier<AdvisoryLocksCondition> conditionSupplier =
                AdvisoryLockConditionSuppliers.get(getLockService(), lockTokens, lockSupplier);
        return runTaskWithConditionWithRetry(conditionSupplier, (transaction, condition) ->
                task.execute(transaction, condition.getLocks()));
    }

    @Override
    public <T, E extends Exception> T runTaskWithLocksWithRetry(
            Supplier<LockRequest> lockSupplier,
            LockAwareTransactionTask<T, E> task)
            throws E, InterruptedException {
        checkOpen();
        return runTaskWithLocksWithRetry(ImmutableList.of(), lockSupplier, task);
    }

    @Override
    public <T, E extends Exception> T runTaskWithLocksThrowOnConflict(
            Iterable<HeldLocksToken> lockTokens,
            LockAwareTransactionTask<T, E> task)
            throws E, TransactionFailedRetriableException {
        checkOpen();
        AdvisoryLocksCondition lockCondition =
                new ExternalLocksCondition(getLockService(), ImmutableSet.copyOf(lockTokens));
        return runTaskWithConditionThrowOnConflict(lockCondition,
                (transaction, condition) -> task.execute(transaction, condition.getLocks()));
    }
}
