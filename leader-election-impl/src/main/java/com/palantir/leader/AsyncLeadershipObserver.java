/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
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

package com.palantir.leader;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import com.google.common.annotations.VisibleForTesting;
import com.palantir.common.concurrent.PTExecutors;

public class AsyncLeadershipObserver implements LeadershipObserver {

    private final ExecutorService executorService;
    private final List<Runnable> leaderTasks = new CopyOnWriteArrayList<>();
    private final List<Runnable> followerTasks = new CopyOnWriteArrayList<>();

    public static AsyncLeadershipObserver create() {
        return new AsyncLeadershipObserver(PTExecutors.newSingleThreadExecutor(true));
    }

    @VisibleForTesting
    AsyncLeadershipObserver(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public synchronized void gainedLeadership() {
        executorService.execute(() -> leaderTasks.forEach(Runnable::run));
    }

    @Override
    public synchronized void lostLeadership() {
        executorService.execute(() -> followerTasks.forEach(Runnable::run));
    }

    @Override
    public synchronized void executeWhenGainedLeadership(Runnable task) {
        leaderTasks.add(task);
    }

    @Override
    public synchronized void executeWhenLostLeadership(Runnable task) {
        followerTasks.add(task);
    }
}