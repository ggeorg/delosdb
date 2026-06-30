/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.derbynet.DrdaThreadingTest

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

import org.apache.derby.impl.drda.ProtocolTestAdapter.ServerConfigurationProbe;
import org.apache.derby.impl.drda.ProtocolTestAdapter.ThreadingProbe;
import org.apache.derbyTesting.junit.BaseTestCase;

/**
 * White-box gate for the DelosDB DRDA server threading policy seam.
 */
public final class DrdaThreadingTest extends BaseTestCase {
    public DrdaThreadingTest(String name) {
        super(name);
    }

    public void testThreadModeParsingDefaultsToPlatform() {
        ServerConfigurationProbe configuration =
                new ServerConfigurationProbe();

        assertEquals("delos.drda.threadMode",
                configuration.threadModePropertyName());
        assertFalse(configuration.usesVirtualWorkers(null));
        assertFalse(configuration.usesVirtualWorkers(""));
        assertFalse(configuration.usesVirtualWorkers("unknown"));
        assertFalse(configuration.usesVirtualWorkers(
                configuration.platformModeName()));
        assertTrue(configuration.usesVirtualWorkers(
                configuration.virtualModeName()));
        assertTrue(configuration.usesVirtualWorkers(" VIRTUAL "));
    }

    public void testPlatformModeStartsPlatformThread() throws Exception {
        ThreadingProbe probe = new ThreadingProbe();

        assertFalse(probe.startedThreadIsVirtual(probe.platformModeName()));
    }

    public void testVirtualModeStartsVirtualThread() throws Exception {
        ThreadingProbe probe = new ThreadingProbe();

        assertTrue(probe.startedThreadIsVirtual(probe.virtualModeName()));
    }
}
