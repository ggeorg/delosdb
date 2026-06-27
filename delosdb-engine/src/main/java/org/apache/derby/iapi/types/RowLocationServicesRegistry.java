/*

   Derby - Class org.apache.derby.iapi.types.RowLocationServicesRegistry

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
package org.apache.derby.iapi.types;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Lazy access point for the engine row-location service used by SQL value
 * classes.
 *
 * <p>Derby's database boot path creates row-location values while the engine is
 * still starting.  At that point the service descriptor may not be visible to
 * every launch shape used by the inherited Derby harness.  The registry
 * therefore prefers the ServiceLoader provider, but it also has a deterministic
 * engine-local fallback for the current monolithic embedded-engine runtime.
 * This keeps boot stable while the row-location/type-system seam remains under
 * extraction.</p>
 */
public final class RowLocationServicesRegistry
{
    private static final String ENGINE_ROW_LOCATION_SERVICES =
            "org.apache.derby.impl.services.storetypes.EngineRowLocationServices";

    private RowLocationServicesRegistry()
    {
    }

    public static RowLocation newRowLocation()
    {
        return Holder.SERVICES.newRowLocation();
    }

    public static RowLocation requireRowLocation(Object value)
    {
        return Holder.SERVICES.requireRowLocation(value);
    }

    private static RowLocationServices load()
    {
        ServiceConfigurationError serviceLoaderError = null;
        try
        {
            RowLocationServices services = loadFromServiceLoader();
            if (services != null)
            {
                return services;
            }
        }
        catch (ServiceConfigurationError error)
        {
            serviceLoaderError = error;
        }

        try
        {
            return loadEngineDefault();
        }
        catch (IllegalStateException ise)
        {
            if (serviceLoaderError != null)
            {
                ise.addSuppressed(serviceLoaderError);
            }
            throw ise;
        }
    }

    private static RowLocationServices loadFromServiceLoader()
    {
        ClassLoader loader = RowLocationServices.class.getClassLoader();
        for (RowLocationServices services : ServiceLoader.load(RowLocationServices.class, loader))
        {
            return services;
        }
        return null;
    }

    private static RowLocationServices loadEngineDefault()
    {
        ClassLoader loader = RowLocationServices.class.getClassLoader();
        try
        {
            Class<?> servicesClass = Class.forName(ENGINE_ROW_LOCATION_SERVICES, true, loader);
            Object services = servicesClass.getConstructor().newInstance();
            if (services instanceof RowLocationServices rowLocationServices)
            {
                return rowLocationServices;
            }
            throw new IllegalStateException(
                    ENGINE_ROW_LOCATION_SERVICES + " does not implement " + RowLocationServices.class.getName());
        }
        catch (ReflectiveOperationException | LinkageError error)
        {
            throw new IllegalStateException("No row-location services provider found", error);
        }
    }

    private static final class Holder
    {
        private static final RowLocationServices SERVICES = load();
    }
}
