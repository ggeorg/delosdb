/*

   Derby - Class org.apache.derby.iapi.store.raw.StoreLockFastPathSupport

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

package org.apache.derby.iapi.store.raw;

import org.apache.derby.iapi.services.locks.LockFastPathSupport;
import org.apache.derby.iapi.services.locks.Lockable;

/** Gives the lock manager the raw store's container and row lock modes. */
public final class StoreLockFastPathSupport implements LockFastPathSupport {
    @Override
    public Class<? extends Lockable> containerLockableClass() {
        return ContainerKey.class;
    }

    @Override
    public Object containerIntentShared() {
        return ContainerLock.CIS;
    }

    @Override
    public Object containerIntentExclusive() {
        return ContainerLock.CIX;
    }

    @Override
    public Object rowShared() {
        return RowLock.RS2;
    }
}
