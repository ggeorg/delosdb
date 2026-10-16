/*

   Derby - Class org.apache.derby.iapi.services.io.DelosHeapObjectDeserializationFilter

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

package org.apache.derby.iapi.services.io;

import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

import org.apache.derby.shared.common.security.DelosObjectInputFilters;

/**
 * DelosDB hardening hook for inherited Derby JAVA_OBJECT deserialization.
 *
 * <p>Bounded resource limits are installed by default. A configured value is
 * interpreted with {@link ObjectInputFilter.Config#createFilter(String)} so
 * deployments can use the JDK's standard object-input-filter grammar. The
 * inherited unbounded behavior requires the explicit compatibility mode in
 * {@link DelosObjectInputFilters}.</p>
 */
public final class DelosHeapObjectDeserializationFilter {
    /**
     * Standard JDK ObjectInputFilter pattern for Derby heap UDT reads.
     * Unset or blank uses DelosDB's bounded default resource limits.
     */
    public static final String FILTER_PROPERTY = DelosObjectInputFilters.HEAP_FILTER_PROPERTY;

    private DelosHeapObjectDeserializationFilter() {
    }

    static void applyIfConfigured(ObjectInputStream stream) {
        DelosObjectInputFilters.applyHeapFilterIfConfigured(stream);
    }
}
