/*

   Derby - Class org.apache.derby.iapi.services.security.StoreSecuritySupportRegistry

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.iapi.services.security;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Locates the engine-side store security bridge.
 */
final class StoreSecuritySupportRegistry
{
    private static final String ENGINE_PROVIDER =
            "org.apache.derby.impl.security.EngineStoreSecuritySupport";

    private static volatile StoreSecuritySupport support;

    private StoreSecuritySupportRegistry()
    {
    }

    static StoreSecuritySupport support()
    {
        StoreSecuritySupport local = support;
        if (local != null)
        {
            return local;
        }

        synchronized (StoreSecuritySupportRegistry.class)
        {
            local = support;
            if (local == null)
            {
                local = serviceLoaderSupport();
                if (local == null)
                {
                    local = classPathFallbackSupport();
                }
                if (local == null)
                {
                    throw new IllegalStateException(
                            "No engine StoreSecuritySupport provider is available");
                }
                support = local;
            }
            return local;
        }
    }

    private static StoreSecuritySupport serviceLoaderSupport()
    {
        Iterator<StoreSecuritySupport> providers =
                ServiceLoader.load(StoreSecuritySupport.class).iterator();
        return providers.hasNext() ? providers.next() : null;
    }

    private static StoreSecuritySupport classPathFallbackSupport()
    {
        try
        {
            Class<?> providerClass = Class.forName(ENGINE_PROVIDER);
            Object provider = providerClass.getDeclaredConstructor().newInstance();
            return (StoreSecuritySupport) provider;
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            return null;
        }
    }
}
