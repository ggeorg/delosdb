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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Utility for optional store cost-controller wrappers. */
public final class StoreCostControllerWrappers {
    private StoreCostControllerWrappers() {
    }

    public static StoreCostController wrap(long conglomerateId, StoreCostController controller)
            throws StandardException {
        StoreCostController wrapped = controller;
        for (StoreCostControllerWrapper wrapper : loadWrappers()) {
            wrapped = wrapper.wrapStoreCostController(conglomerateId, wrapped);
        }
        return wrapped;
    }

    private static Iterable<StoreCostControllerWrapper> loadWrappers() {
        Map<String, StoreCostControllerWrapper> wrappersByClass = new LinkedHashMap<>();

        ModuleLayer layer = StoreCostControllerWrapper.class.getModule().getLayer();
        if (layer != null) {
            addWrappers(wrappersByClass, ServiceLoader.load(layer, StoreCostControllerWrapper.class));
        }

        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        addWrappers(wrappersByClass, contextLoader);
        addWrappers(wrappersByClass, StoreCostControllerWrappers.class.getClassLoader());
        addWrappers(wrappersByClass, StoreCostControllerWrapper.class.getClassLoader());
        addWrappers(wrappersByClass, ClassLoader.getSystemClassLoader());
        addNamedWrapper(wrappersByClass, "io.github.ggeorg.delosdb.engine.extension.cost.StoreCostControllerBridge");

        return wrappersByClass.values();
    }

    private static void addWrappers(
            Map<String, StoreCostControllerWrapper> wrappersByClass,
            ClassLoader loader) {
        if (loader == null) {
            return;
        }
        addWrappers(wrappersByClass, ServiceLoader.load(StoreCostControllerWrapper.class, loader));
    }

    private static void addNamedWrapper(
            Map<String, StoreCostControllerWrapper> wrappersByClass,
            String className) {
        if (wrappersByClass.containsKey(className)) {
            return;
        }
        Class<?> providerClass = loadClass(className);
        if (providerClass == null || !StoreCostControllerWrapper.class.isAssignableFrom(providerClass)) {
            return;
        }
        try {
            StoreCostControllerWrapper wrapper =
                    (StoreCostControllerWrapper) providerClass.getDeclaredConstructor().newInstance();
            wrappersByClass.putIfAbsent(className, wrapper);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // The named fallback is optional.  ServiceLoader remains the normal
            // path, and Derby must keep working when the DelosDB engine provider
            // is not visible in unusual class-loading tests.
        }
    }

    private static Class<?> loadClass(String className) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader[] loaders = new ClassLoader[] {
                contextLoader,
                StoreCostControllerWrappers.class.getClassLoader(),
                StoreCostControllerWrapper.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(className, true, loader);
            } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
                // Try the next loader.
            }
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static void addWrappers(
            Map<String, StoreCostControllerWrapper> wrappersByClass,
            ServiceLoader<StoreCostControllerWrapper> loader) {
        Iterator<StoreCostControllerWrapper> wrappers = loader.iterator();
        while (true) {
            StoreCostControllerWrapper wrapper;
            try {
                if (!wrappers.hasNext()) {
                    break;
                }
                wrapper = wrappers.next();
            } catch (ServiceConfigurationError error) {
                // Some Derby class-loading tests intentionally alter the
                // application class path/class loader.  A stale or incompatible
                // provider must not make the native Derby optimizer unusable;
                // the extension point is optional, so fall back to the delegate.
                continue;
            }
            wrappersByClass.putIfAbsent(wrapper.getClass().getName(), wrapper);
        }
    }
}
