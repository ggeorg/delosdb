/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.derbynet.DrdaExtdtaSpoolTest

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
package org.apache.derbyTesting.functionTests.tests.derbynet;

import java.io.IOException;
import org.apache.derby.impl.drda.ProtocolTestAdapter.ExtdtaSpoolProbe;
import org.apache.derby.impl.drda.ProtocolTestAdapter.ServerConfigurationProbe;
import org.apache.derby.impl.drda.ProtocolTestAdapter.MaterializedProbe;
import org.apache.derby.shared.common.reference.DRDAConstants;
import org.apache.derbyTesting.junit.BaseTestCase;

/**
 * White-box gate for DRDA EXTDTA materialization memory safety.
 */
public final class DrdaExtdtaSpoolTest extends BaseTestCase {
    public DrdaExtdtaSpoolTest(String name) {
        super(name);
    }


    public void testExtdtaSpoolThresholdConfigurationParsing() {
        ServerConfigurationProbe configuration =
                new ServerConfigurationProbe();

        assertEquals("delos.drda.extdta.spoolThresholdBytes",
                configuration.extdtaSpoolThresholdPropertyName());
        assertEquals(4096, configuration.extdtaSpoolThresholdBytes("4096"));
        assertEquals(Integer.MAX_VALUE,
                configuration.extdtaSpoolThresholdBytes(
                        Long.toString(((long) Integer.MAX_VALUE) + 4096L)));
        assertEquals(configuration.defaultExtdtaSpoolThresholdBytes(),
                configuration.extdtaSpoolThresholdBytes(null));
        assertEquals(configuration.defaultExtdtaSpoolThresholdBytes(),
                configuration.extdtaSpoolThresholdBytes(""));
        assertEquals(configuration.defaultExtdtaSpoolThresholdBytes(),
                configuration.extdtaSpoolThresholdBytes("0"));
        assertEquals(configuration.defaultExtdtaSpoolThresholdBytes(),
                configuration.extdtaSpoolThresholdBytes("not-a-number"));
    }

    public void testSmallExtdtaKeepsInheritedHeapFastPath()
            throws Exception {
        byte[] payload = payload(64);
        ExtdtaSpoolProbe probe = new ExtdtaSpoolProbe();

        MaterializedProbe materialized = probe.materialize(payload, 1024);

        assertFalse(materialized.isSpooled());
        assertEquals(payload.length, materialized.byteLength());
        assertFalse(materialized.temporaryFileExists());
        assertByteArrayEquals(payload, materialized.readAllAndClose());
    }

    public void testLargeExtdtaSpoolsAndDeletesTemporaryFile()
            throws Exception {
        byte[] payload = payload(64 * 1024);
        ExtdtaSpoolProbe probe = new ExtdtaSpoolProbe();

        MaterializedProbe materialized = probe.materialize(payload, 1024);

        assertTrue(materialized.isSpooled());
        assertEquals(payload.length, materialized.byteLength());
        assertTrue(materialized.temporaryFileExists());
        assertByteArrayEquals(payload, materialized.readAllAndClose());
        assertFalse(materialized.temporaryFileExists());
    }

    public void testClientSideExtdtaFailureStillFailsEmbeddedRead()
            throws Exception {
        byte[] payload = payload(64 * 1024);
        ExtdtaSpoolProbe probe = new ExtdtaSpoolProbe();

        MaterializedProbe materialized = probe.materialize(
                payload,
                1024,
                DRDAConstants.STREAM_READ_ERROR);

        assertFalse(materialized.isSpooled());
        assertEquals(payload.length, materialized.byteLength());
        assertFalse(materialized.temporaryFileExists());
        try {
            materialized.assertFailsOnRead();
            fail("client-side EXTDTA error should fail when embedded reads");
        } catch (IOException expected) {
            // expected
        }
    }

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static void assertByteArrayEquals(byte[] expected, byte[] actual) {
        assertEquals("array length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("array element " + i, expected[i], actual[i]);
        }
    }
}
