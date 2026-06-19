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
                current = ServiceLoader.load(MonitorKernelSupport.class)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "No engine MonitorKernelSupport provider is available"));
                support = current;
            }
            return current;
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
