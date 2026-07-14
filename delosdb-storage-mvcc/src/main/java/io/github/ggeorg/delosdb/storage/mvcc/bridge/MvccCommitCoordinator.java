/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccCommitCoordinator

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-table boundary between prepared commits and ordered durable publication.
 *
 * <p>The queued mode is intentionally not group commit. Each enrolled
 * transaction still performs its own complete durability operation. The queue
 * only makes bounded FIFO enrollment explicit so a later phase can group
 * already-prepared transactions without changing transaction preparation.</p>
 */
final class MvccCommitCoordinator {
    static final int DEFAULT_CAPACITY = 64;

    enum Mode {
        DIRECT("direct"),
        QUEUED("queued");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final Mode mode;
    private final ReentrantLock directLock = new ReentrantLock(true);
    private final Semaphore queueCapacity;
    private final ReentrantLock queueLock = new ReentrantLock(true);
    private final Condition queueChanged = queueLock.newCondition();
    private final ArrayDeque<Ticket> queue = new ArrayDeque<>();

    MvccCommitCoordinator(Mode mode) {
        this(mode, DEFAULT_CAPACITY);
    }

    MvccCommitCoordinator(Mode mode, int capacity) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        queueCapacity = new Semaphore(capacity, true);
    }

    Permit enter(boolean measureWait) {
        long started = measureWait ? System.nanoTime() : 0L;
        if (mode == Mode.DIRECT) {
            directLock.lock();
            return new Permit(
                    this,
                    null,
                    1,
                    measureWait ? System.nanoTime() - started : 0L);
        }

        queueCapacity.acquireUninterruptibly();
        Ticket ticket = new Ticket();
        int enrollmentDepth;
        queueLock.lock();
        try {
            queue.addLast(ticket);
            enrollmentDepth = queue.size();
            while (queue.peekFirst() != ticket) {
                queueChanged.awaitUninterruptibly();
            }
        } finally {
            queueLock.unlock();
        }
        return new Permit(
                this,
                ticket,
                enrollmentDepth,
                measureWait ? System.nanoTime() - started : 0L);
    }

    Mode mode() {
        return mode;
    }

    int currentEnrollmentCountForTesting() {
        if (mode == Mode.DIRECT) {
            return directLock.isLocked() ? 1 : 0;
        }
        queueLock.lock();
        try {
            return queue.size();
        } finally {
            queueLock.unlock();
        }
    }

    private void leave(Ticket ticket) {
        if (mode == Mode.DIRECT) {
            directLock.unlock();
            return;
        }

        queueLock.lock();
        try {
            if (queue.peekFirst() != ticket) {
                throw new IllegalStateException("MVCC durability enrollment left out of FIFO order");
            }
            queue.removeFirst();
            queueChanged.signalAll();
        } finally {
            queueLock.unlock();
            queueCapacity.release();
        }
    }

    static final class Permit implements AutoCloseable {
        private MvccCommitCoordinator coordinator;
        private final Ticket ticket;
        private final int enrollmentDepth;
        private final long waitNanos;

        private Permit(
                MvccCommitCoordinator coordinator,
                Ticket ticket,
                int enrollmentDepth,
                long waitNanos) {
            this.coordinator = coordinator;
            this.ticket = ticket;
            this.enrollmentDepth = enrollmentDepth;
            this.waitNanos = waitNanos;
        }

        Mode mode() {
            return coordinator.mode();
        }

        int enrollmentDepth() {
            return enrollmentDepth;
        }

        long waitNanos() {
            return waitNanos;
        }

        @Override
        public void close() {
            MvccCommitCoordinator owner = coordinator;
            if (owner != null) {
                owner.leave(ticket);
                coordinator = null;
            }
        }
    }

    private static final class Ticket {
    }
}
