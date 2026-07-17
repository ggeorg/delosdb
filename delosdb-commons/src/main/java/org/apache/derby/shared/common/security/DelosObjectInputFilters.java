/*

   Derby - Class org.apache.derby.shared.common.security.DelosObjectInputFilters

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

package org.apache.derby.shared.common.security;

import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * JDK object-input filters for inherited Derby deserialization sites.
 *
 * <p>DelosDB installs bounded resource limits by default while preserving
 * Derby's historical class compatibility. Deployments may replace the default
 * pattern with the standard JDK object-input-filter grammar. The unbounded
 * Derby-compatible behavior is available only through the explicit
 * compatibility-mode property.</p>
 */
public final class DelosObjectInputFilters {
    /** General filter for non-store ObjectInputStream leaf sites. */
    public static final String GENERAL_FILTER_PROPERTY = "delosdb.objectDeserializationFilter";

    /** Store-specific filter for Derby heap JAVA_OBJECT reads. */
    public static final String HEAP_FILTER_PROPERTY = "delosdb.heap.objectDeserializationFilter";

    /**
     * Explicitly restores inherited unbounded deserialization behavior when
     * set to {@code true}. A configured general or heap filter still wins.
     */
    public static final String COMPATIBILITY_MODE_PROPERTY =
            "delosdb.objectDeserializationCompatibilityMode";

    /**
     * Compatibility-preserving class policy with bounded graph and byte use.
     * JDK resource limits are evaluated before the final class wildcard.
     */
    public static final String DEFAULT_FILTER_PATTERN =
            "maxdepth=32;maxrefs=100000;maxbytes=16777216;maxarray=100000;*";

    private DelosObjectInputFilters() {
    }

    public static void applyGeneralFilterIfConfigured(ObjectInputStream stream) {
        apply(stream, GENERAL_FILTER_PROPERTY);
    }

    public static void applyHeapFilterIfConfigured(ObjectInputStream stream) {
        apply(stream, HEAP_FILTER_PROPERTY);
    }

    private static void apply(ObjectInputStream stream, String propertyName) {
        String configuredPattern = System.getProperty(propertyName);
        if (configuredPattern != null && !configuredPattern.isBlank()) {
            stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(configuredPattern));
            return;
        }

        if (Boolean.getBoolean(COMPATIBILITY_MODE_PROPERTY)) {
            return;
        }

        stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(DEFAULT_FILTER_PATTERN));
    }
}
