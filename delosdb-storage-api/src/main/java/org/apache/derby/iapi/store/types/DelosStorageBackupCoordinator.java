/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator

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
package org.apache.derby.iapi.store.types;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Process-local boundary between MVCC durable mutations and online backup.
 *
 * <p>Normal MVCC durable mutations take a shared guard. The raw-store backup
 * copier takes the exclusive guard, producing one cross-subsystem sidecar
 * image instead of independently sampled page, outcome, and recovery files.</p>
 */
public final class DelosStorageBackupCoordinator {
    private static final ReentrantReadWriteLock BOUNDARY = new ReentrantReadWriteLock(true);

    private DelosStorageBackupCoordinator() {
    }

    public static Guard enterDurableMutation() {
        return acquire(BOUNDARY.readLock());
    }

    public static Guard enterBackupSnapshot() {
        return acquire(BOUNDARY.writeLock());
    }

    private static Guard acquire(Lock lock) {
        lock.lock();
        return new Guard(lock);
    }

    public static final class Guard implements AutoCloseable {
        private Lock lock;

        private Guard(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            Lock held = lock;
            if (held != null) {
                lock = null;
                held.unlock();
            }
        }
    }
}
