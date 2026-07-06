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
 * Optional JDK object-input filters for inherited Derby deserialization sites.
 *
 * <p>The default is Derby-compatible: no per-stream filter is installed unless
 * the corresponding system property is explicitly configured. Configured values
 * use {@link ObjectInputFilter.Config#createFilter(String)} and therefore follow
 * the standard JDK object-input-filter grammar.</p>
 */
public final class DelosObjectInputFilters {
    /**
     * General opt-in filter for non-store ObjectInputStream leaf sites such as
     * DRDA UDT parameters, client UDT materialization, import UDT values, and
     * replication control messages.
     */
    public static final String GENERAL_FILTER_PROPERTY = "delosdb.objectDeserializationFilter";

    /**
     * Store-specific opt-in filter for Derby heap JAVA_OBJECT reads through
     * FormatIdInputStream.
     */
    public static final String HEAP_FILTER_PROPERTY = "delosdb.heap.objectDeserializationFilter";

    private DelosObjectInputFilters() {
    }

    public static void applyGeneralFilterIfConfigured(ObjectInputStream stream) {
        applyIfConfigured(stream, GENERAL_FILTER_PROPERTY);
    }

    public static void applyHeapFilterIfConfigured(ObjectInputStream stream) {
        applyIfConfigured(stream, HEAP_FILTER_PROPERTY);
    }

    private static void applyIfConfigured(ObjectInputStream stream, String propertyName) {
        String pattern = System.getProperty(propertyName);
        if (pattern == null || pattern.isBlank()) {
            return;
        }

        stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(pattern));
    }
}
