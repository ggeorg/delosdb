/*

   Derby - Class org.apache.derby.iapi.store.access.StoreCostControllerWrappers

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

package org.apache.derby.iapi.store.access;

import org.apache.derby.shared.common.error.StandardException;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Utility for optional store cost-controller wrappers. */
public final class StoreCostControllerWrappers {
    private StoreCostControllerWrappers() {
    }

    public static StoreCostController wrap(long conglomerateId, StoreCostController controller)
            throws StandardException {
        StoreCostController wrapped = controller;
        Iterator<StoreCostControllerWrapper> wrappers =
            ServiceLoader.load(StoreCostControllerWrapper.class).iterator();

        while (true) {
            StoreCostControllerWrapper wrapper;
            try {
                if (!wrappers.hasNext()) {
                    break;
                }
                wrapper = wrappers.next();
            }
            catch (ServiceConfigurationError error) {
                // Some Derby class-loading tests intentionally alter the
                // application class path/class loader.  A stale or incompatible
                // provider must not make the native Derby optimizer unusable;
                // the extension point is optional, so fall back to the delegate.
                continue;
            }
            wrapped = wrapper.wrapStoreCostController(conglomerateId, wrapped);
        }
        return wrapped;
    }
}
