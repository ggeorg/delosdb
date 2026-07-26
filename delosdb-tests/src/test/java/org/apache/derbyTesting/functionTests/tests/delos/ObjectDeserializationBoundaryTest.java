/*

   DelosDB - Class org.apache.derbyTesting.functionTests.tests.delos.ObjectDeserializationBoundaryTest

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
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.derby.shared.common.security.DelosObjectInputFilters;

import junit.framework.TestCase;

/** Proves fail-closed external boundaries and the separate heap policy. */
public final class ObjectDeserializationBoundaryTest extends TestCase {
    private static final String[] FILTER_PROPERTIES = {
            DelosObjectInputFilters.GENERAL_FILTER_PROPERTY,
            DelosObjectInputFilters.DRDA_FILTER_PROPERTY,
            DelosObjectInputFilters.IMPORT_FILTER_PROPERTY,
            DelosObjectInputFilters.REPLICATION_FILTER_PROPERTY,
            DelosObjectInputFilters.HEAP_FILTER_PROPERTY,
            DelosObjectInputFilters.COMPATIBILITY_MODE_PROPERTY,
            DelosObjectInputFilters.HEAP_COMPATIBILITY_MODE_PROPERTY
    };

    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @Override
    protected void setUp() {
        for (String property : FILTER_PROPERTIES) {
            previousProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @Override
    protected void tearDown() {
        for (Map.Entry<String, String> entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        previousProperties.clear();
    }

    public void testDrdaAndImportRejectUnexpectedClassesByDefault() throws Exception {
        assertRejected(
                new UnexpectedPayload(10),
                DelosObjectInputFilters::applyDrdaFilterIfConfigured);
        assertRejected(
                new UnexpectedPayload(20),
                DelosObjectInputFilters::applyImportFilterIfConfigured);
    }

    public void testImportMetadataUsesFixedInternalAllowList() throws Exception {
        ArrayList<String> columnTypes = new ArrayList<>();
        columnTypes.add("INTEGER");
        columnTypes.add("VARCHAR(20)");
        assertEquals(
                columnTypes,
                read(columnTypes, DelosObjectInputFilters::applyImportMetadataFilter));

        HashMap<String, String> udtClassNames = new HashMap<>();
        udtClassNames.put("COLUMN1", "com.example.SafeValue");
        assertEquals(
                udtClassNames,
                read(udtClassNames, DelosObjectInputFilters::applyImportMetadataFilter));

        assertRejected(
                columnTypes,
                DelosObjectInputFilters::applyImportFilterIfConfigured);
        assertRejected(
                new LinkedHashMap<>(udtClassNames),
                DelosObjectInputFilters::applyImportMetadataFilter);
        assertRejected(
                new UnexpectedPayload(25),
                DelosObjectInputFilters::applyImportMetadataFilter);

        System.setProperty(
                DelosObjectInputFilters.IMPORT_FILTER_PROPERTY,
                UnexpectedPayload.class.getName() + ";java.base/*;!*");
        assertPayload(
                read(new UnexpectedPayload(30),
                        DelosObjectInputFilters::applyImportFilterIfConfigured),
                30);
        assertRejected(
                new UnexpectedPayload(35),
                DelosObjectInputFilters::applyImportMetadataFilter);
    }

    public void testExplicitBoundaryAllowListsOverrideFailClosedDefaults() throws Exception {
        String allowPayload = UnexpectedPayload.class.getName() + ";java.base/*;!*";
        System.setProperty(DelosObjectInputFilters.GENERAL_FILTER_PROPERTY, allowPayload);

        assertPayload(
                read(new UnexpectedPayload(30),
                        DelosObjectInputFilters::applyImportFilterIfConfigured),
                30);
        assertRejected(
                new UnexpectedPayload(35),
                DelosObjectInputFilters::applyReplicationFilterIfConfigured);

        System.setProperty(
                DelosObjectInputFilters.DRDA_FILTER_PROPERTY,
                DelosObjectInputFilters.DEFAULT_EXTERNAL_FILTER_PATTERN);
        assertRejected(
                new UnexpectedPayload(40),
                DelosObjectInputFilters::applyDrdaFilterIfConfigured);

        System.setProperty(DelosObjectInputFilters.DRDA_FILTER_PROPERTY, allowPayload);
        assertPayload(
                read(new UnexpectedPayload(50),
                        DelosObjectInputFilters::applyDrdaFilterIfConfigured),
                50);
    }

    public void testReplicationDefaultAllowsOnlyProtocolShapes() throws Exception {
        assertEquals(Long.valueOf(60), read(
                Long.valueOf(60),
                DelosObjectInputFilters::applyReplicationFilterIfConfigured));
        assertTrue(read(
                new byte[] {1, 2, 3},
                DelosObjectInputFilters::applyReplicationFilterIfConfigured) instanceof byte[]);
        assertTrue(read(
                new String[] {"08006", "failure"},
                DelosObjectInputFilters::applyReplicationFilterIfConfigured) instanceof String[]);
        assertEquals("ack", read(
                "ack",
                DelosObjectInputFilters::applyReplicationFilterIfConfigured));

        assertRejected(
                new UnexpectedPayload(70),
                DelosObjectInputFilters::applyReplicationFilterIfConfigured);
    }

    public void testExternalAndHeapCompatibilityModesAreSeparate() throws Exception {
        System.setProperty(DelosObjectInputFilters.COMPATIBILITY_MODE_PROPERTY, "true");
        assertPayload(
                read(new UnexpectedPayload(80),
                        DelosObjectInputFilters::applyDrdaFilterIfConfigured),
                80);

        DeepPayload deepPayload = DeepPayload.chain(40);
        assertRejected(
                deepPayload,
                DelosObjectInputFilters::applyHeapFilterIfConfigured);

        System.setProperty(
                DelosObjectInputFilters.HEAP_COMPATIBILITY_MODE_PROPERTY,
                "true");
        assertTrue(read(
                deepPayload,
                DelosObjectInputFilters::applyHeapFilterIfConfigured) instanceof DeepPayload);
    }

    private static Object read(
            Serializable payload,
            FilterInstaller installer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(payload);
        }

        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            installer.install(input);
            return input.readObject();
        }
    }

    private static void assertRejected(
            Serializable payload,
            FilterInstaller installer) throws Exception {
        try {
            read(payload, installer);
            fail("Expected the object-input filter to reject " + payload.getClass().getName());
        } catch (InvalidClassException expected) {
            assertTrue("expected ObjectInputFilter rejection, got: " + expected,
                    expected.getMessage() != null
                            && (expected.getMessage().contains("filter")
                            || expected.getMessage().contains("REJECTED")));
        }
    }

    private static void assertPayload(Object value, int expectedValue) {
        assertTrue("unexpected payload class: " + value,
                value instanceof UnexpectedPayload);
        assertEquals(expectedValue, ((UnexpectedPayload) value).value);
    }

    @FunctionalInterface
    private interface FilterInstaller {
        void install(ObjectInputStream stream);
    }

    private static final class UnexpectedPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;

        private UnexpectedPayload(int value) {
            this.value = value;
        }
    }

    private static final class DeepPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final DeepPayload next;

        private DeepPayload(DeepPayload next) {
            this.next = next;
        }

        private static DeepPayload chain(int depth) {
            DeepPayload value = null;
            for (int index = 0; index < depth; index++) {
                value = new DeepPayload(value);
            }
            return value;
        }
    }
}
