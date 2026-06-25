/*

   Derby - Class org.apache.derby.iapi.services.monitor.MonitorKernelSupport

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

import java.io.PrintWriter;
import java.util.Properties;
import org.apache.derby.iapi.services.context.ContextKernelSupport;

/**
 * Engine-owned support seam used while the inherited Derby monitor facade is
 * extracted into the DelosDB engine kernel.
 *
 * <p>The monitor API is store-facing boot infrastructure, but its concrete
 * implementation still lives in the engine during B3b. This seam keeps the
 * kernel-owned monitor package from depending upward on FileMonitor,
 * SecurityUtil, or PropertyUtil.</p>
 */
public interface MonitorKernelSupport
{
    void checkDerbyInternalsPrivilege();

    ModuleFactory createFileMonitor(Properties bootProperties, PrintWriter logging);

    ModuleFactory createLiteFileMonitor();

    boolean getSystemBoolean(String key);

    ContextKernelSupport contextKernelSupport();
}
