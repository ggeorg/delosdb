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
 * <p>External object boundaries fail closed by default. DRDA UDT values and
 * import UDT values require an explicit allow-list or the explicit external
 * compatibility switch. Replication keeps a narrow protocol allow-list.
 * Heap {@code JAVA_OBJECT} reads use a separate, compatibility-preserving,
 * resource-bounded policy because the database contents are an application
 * persistence contract rather than an unauthenticated transport boundary.</p>
 */
public final class DelosObjectInputFilters {
    /** Backward-compatible common override for external deserialization sites. */
    public static final String GENERAL_FILTER_PROPERTY = "delosdb.objectDeserializationFilter";

    /** DRDA client/server UDT deserialization override. */
    public static final String DRDA_FILTER_PROPERTY =
            "delosdb.drda.objectDeserializationFilter";

    /** IMPORT UDT deserialization override. */
    public static final String IMPORT_FILTER_PROPERTY =
            "delosdb.import.objectDeserializationFilter";

    /** Replication protocol deserialization override. */
    public static final String REPLICATION_FILTER_PROPERTY =
            "delosdb.replication.objectDeserializationFilter";

    /** Store-specific filter for Derby heap JAVA_OBJECT reads. */
    public static final String HEAP_FILTER_PROPERTY =
            "delosdb.heap.objectDeserializationFilter";

    /**
     * Restores inherited unfiltered behavior for external DRDA, import, and
     * replication deserialization when set to {@code true}. A configured
     * boundary-specific or common external filter still wins.
     */
    public static final String COMPATIBILITY_MODE_PROPERTY =
            "delosdb.objectDeserializationCompatibilityMode";

    /**
     * Restores inherited unfiltered behavior for heap JAVA_OBJECT reads when
     * set to {@code true}. The external compatibility switch does not affect
     * heap persistence.
     */
    public static final String HEAP_COMPATIBILITY_MODE_PROPERTY =
            "delosdb.heap.objectDeserializationCompatibilityMode";

    /** Resource limits shared by all default policies. */
    public static final String RESOURCE_LIMIT_PATTERN =
            "maxdepth=32;maxrefs=100000;maxbytes=16777216;maxarray=100000";

    /** Heap compatibility policy: bounded resources, existing classes allowed. */
    public static final String DEFAULT_HEAP_FILTER_PATTERN =
            RESOURCE_LIMIT_PATTERN + ";*";

    /** External UDT/import policy: no application class is accepted by default. */
    public static final String DEFAULT_EXTERNAL_FILTER_PATTERN =
            RESOURCE_LIMIT_PATTERN + ";!*";

    /**
     * Replication protocol policy. Replication messages contain only the
     * envelope, Long handshake values, byte-array log payloads, Strings, and
     * String-array error payloads defined by ReplicationMessage.
     */
    public static final String DEFAULT_REPLICATION_FILTER_PATTERN =
            RESOURCE_LIMIT_PATTERN
                    + ";org.apache.derby.impl.store.replication.net.ReplicationMessage"
                    + ";java.lang.Long"
                    + ";java.lang.Number"
                    + ";java.lang.String"
                    + ";[B"
                    + ";[Ljava.lang.String;!*";

    private DelosObjectInputFilters() {
    }

    /** Install the fail-closed DRDA UDT policy. */
    public static void applyDrdaFilterIfConfigured(ObjectInputStream stream) {
        applyExternalBoundary(
                stream,
                DRDA_FILTER_PROPERTY,
                GENERAL_FILTER_PROPERTY,
                DEFAULT_EXTERNAL_FILTER_PATTERN);
    }

    /** Install the fail-closed IMPORT UDT policy. */
    public static void applyImportFilterIfConfigured(ObjectInputStream stream) {
        applyExternalBoundary(
                stream,
                IMPORT_FILTER_PROPERTY,
                GENERAL_FILTER_PROPERTY,
                DEFAULT_EXTERNAL_FILTER_PATTERN);
    }

    /** Install the narrow replication protocol allow-list. */
    public static void applyReplicationFilterIfConfigured(ObjectInputStream stream) {
        applyExternalBoundary(
                stream,
                REPLICATION_FILTER_PROPERTY,
                null,
                DEFAULT_REPLICATION_FILTER_PATTERN);
    }

    /** Install the separate heap JAVA_OBJECT persistence policy. */
    public static void applyHeapFilterIfConfigured(ObjectInputStream stream) {
        String configuredPattern = configuredPattern(HEAP_FILTER_PROPERTY, null);
        if (configuredPattern != null) {
            install(stream, configuredPattern);
            return;
        }

        if (Boolean.getBoolean(HEAP_COMPATIBILITY_MODE_PROPERTY)) {
            return;
        }

        install(stream, DEFAULT_HEAP_FILTER_PATTERN);
    }

    private static void applyExternalBoundary(
            ObjectInputStream stream,
            String boundaryProperty,
            String fallbackProperty,
            String defaultPattern) {
        String configuredPattern = configuredPattern(
                boundaryProperty,
                fallbackProperty);
        if (configuredPattern != null) {
            install(stream, configuredPattern);
            return;
        }

        if (Boolean.getBoolean(COMPATIBILITY_MODE_PROPERTY)) {
            return;
        }

        install(stream, defaultPattern);
    }

    private static String configuredPattern(
            String primaryProperty,
            String fallbackProperty) {
        String configuredPattern = nonBlankSystemProperty(primaryProperty);
        if (configuredPattern != null || fallbackProperty == null) {
            return configuredPattern;
        }
        return nonBlankSystemProperty(fallbackProperty);
    }

    private static String nonBlankSystemProperty(String propertyName) {
        String value = System.getProperty(propertyName);
        return value == null || value.isBlank() ? null : value;
    }

    private static void install(ObjectInputStream stream, String pattern) {
        stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(pattern));
    }
}
