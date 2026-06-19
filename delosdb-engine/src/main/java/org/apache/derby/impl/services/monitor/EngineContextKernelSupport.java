/*

   Derby - Class org.apache.derby.iapi.services.monitor.EngineContextKernelSupport

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

import java.util.Locale;
import org.apache.derby.iapi.services.context.ContextKernelSupport;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.services.property.PropertyUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.stream.HeaderPrintWriter;

/** Engine implementation of the kernel-owned context support seam. */
final class EngineContextKernelSupport implements ContextKernelSupport
{
    @Override
    public HeaderPrintWriter getErrorStream()
    {
        return Monitor.getStream();
    }

    @Override
    public Locale getLocaleFromString(String localeID) throws StandardException
    {
        return Monitor.getLocaleFromString(localeID);
    }

    @Override
    public int getSystemInt(String key, int min, int max, int defaultValue)
    {
        return PropertyUtil.getSystemInt(key, min, max, defaultValue);
    }

    @Override
    public void shutdownSystem()
    {
        Monitor.getMonitor().shutdown();
    }
}
