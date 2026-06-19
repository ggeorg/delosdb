/*

   Derby - Class org.apache.derby.impl.services.monitor.EngineMonitorKernelSupport

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

package org.apache.derby.impl.services.monitor;

import java.io.PrintWriter;
import java.util.Properties;
import org.apache.derby.iapi.security.SecurityUtil;
import org.apache.derby.iapi.services.context.ContextKernelSupport;
import org.apache.derby.iapi.services.monitor.ModuleFactory;
import org.apache.derby.iapi.services.monitor.MonitorKernelSupport;
import org.apache.derby.iapi.services.property.PropertyUtil;

/** Engine implementation of the kernel-owned monitor support seam. */
public final class EngineMonitorKernelSupport implements MonitorKernelSupport
{
    private final ContextKernelSupport contextKernelSupport = new EngineContextKernelSupport();

    @Override
    public void checkDerbyInternalsPrivilege()
    {
        SecurityUtil.checkDerbyInternalsPrivilege();
    }

    @Override
    public ModuleFactory createFileMonitor(Properties bootProperties, PrintWriter logging)
    {
        return new FileMonitor(bootProperties, logging);
    }

    @Override
    public ModuleFactory createLiteFileMonitor()
    {
        return new FileMonitor();
    }

    @Override
    public boolean getSystemBoolean(String key)
    {
        return PropertyUtil.getSystemBoolean(key);
    }

    @Override
    public ContextKernelSupport contextKernelSupport()
    {
        return contextKernelSupport;
    }
}
