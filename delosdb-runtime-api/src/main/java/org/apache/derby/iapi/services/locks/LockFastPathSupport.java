/*

   Derby - Class org.apache.derby.iapi.services.locks.LockFastPathSupport

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

package org.apache.derby.iapi.services.locks;

/**
 * The store's lock modes that the lock manager grants on its fast paths.
 * The lock manager compares requests against these by identity, so it needs
 * no store type.
 */
public interface LockFastPathSupport {
    /** @return the class of the lockables that name a container */
    Class<? extends Lockable> containerLockableClass();

    /** @return the qualifier of an intent-shared container lock */
    Object containerIntentShared();

    /** @return the qualifier of an intent-exclusive container lock */
    Object containerIntentExclusive();

    /** @return the qualifier of a shared row lock taken to read a row */
    Object rowShared();
}
