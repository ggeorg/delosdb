/*

   Derby - Class org.apache.derby.iapi.services.monitor.MonitorKernelSupportRegistry

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

package org.apache.derby.iapi.services.monitor;

import java.util.ServiceLoader;

/** Resolves the engine-side monitor support implementation without a kernel-to-engine dependency. */
public final class MonitorKernelSupportRegistry
{
    private static volatile MonitorKernelSupport support;

    private MonitorKernelSupportRegistry()
    {
    }

    public static MonitorKernelSupport support()
    {
        MonitorKernelSupport current = support;
        if (current != null) {
            return current;
        }
        synchronized (MonitorKernelSupportRegistry.class) {
            current = support;
            if (current == null) {
                current = serviceLoaderSupport();
                if (current == null) {
                    current = classPathFallbackSupport();
                }
                if (current == null) {
                    throw new IllegalStateException(
                            "No engine MonitorKernelSupport provider is available");
                }
                support = current;
            }
            return current;
        }
    }

    private static MonitorKernelSupport serviceLoaderSupport()
    {
        return ServiceLoader.load(MonitorKernelSupport.class)
                .findFirst()
                .orElse(null);
    }

    /**
     * Derby's inherited language suite executes the runtime jars on the classpath.
     * In that shape, JPMS {@code provides} clauses are not service descriptors.
     * Keep ServiceLoader as the module-path path, but fall back to the engine
     * provider by name so classpath-era Derby boot still works.
     */
    private static MonitorKernelSupport classPathFallbackSupport()
    {
        try {
            Class<?> providerClass = Class.forName(
                    "org.apache.derby.impl.services.monitor.EngineMonitorKernelSupport",
                    true,
                    MonitorKernelSupportRegistry.class.getClassLoader());
            Object provider = providerClass.getDeclaredConstructor().newInstance();
            return (MonitorKernelSupport) provider;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static void install(MonitorKernelSupport newSupport)
    {
        if (newSupport == null) {
            throw new NullPointerException("newSupport");
        }
        support = newSupport;
    }
}
