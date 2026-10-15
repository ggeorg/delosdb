/*

   Derby - Class org.apache.derby.impl.services.locks.LockFastPaths

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

package org.apache.derby.impl.services.locks;

import java.util.ServiceLoader;

import org.apache.derby.iapi.services.locks.LockFastPathSupport;
import org.apache.derby.iapi.services.locks.Lockable;

/**
 * The store's lock modes that the fast paths grant, read once. Without a
 * provider the qualifiers match no request and the fast paths stay unused.
 */
final class LockFastPaths {
    private static final LockFastPathSupport SUPPORT = loadSupport();

    /** The class of container lockables, or null. */
    static final Class<? extends Lockable> CONTAINER_LOCKABLE =
            SUPPORT == null ? null : SUPPORT.containerLockableClass();
    static final Object CONTAINER_INTENT_SHARED =
            SUPPORT == null ? new Object() : SUPPORT.containerIntentShared();
    static final Object CONTAINER_INTENT_EXCLUSIVE =
            SUPPORT == null ? new Object() : SUPPORT.containerIntentExclusive();
    static final Object ROW_SHARED =
            SUPPORT == null ? new Object() : SUPPORT.rowShared();

    private LockFastPaths() {
    }

    private static LockFastPathSupport loadSupport() {
        return ServiceLoader.load(LockFastPathSupport.class)
                .findFirst()
                .orElseGet(LockFastPaths::loadClasspathSupportOrNull);
    }

    private static LockFastPathSupport loadClasspathSupportOrNull() {
        try {
            Class<?> providerClass = Class.forName(
                    "org.apache.derby.iapi.store.raw.StoreLockFastPathSupport");
            return (LockFastPathSupport) providerClass
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
