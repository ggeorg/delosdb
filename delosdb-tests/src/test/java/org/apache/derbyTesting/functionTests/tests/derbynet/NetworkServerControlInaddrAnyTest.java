/*

Derby - Class org.apache.derbyTesting.functionTests.tests.derbynet.NetworkServerControlInaddrAnyTest

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

import java.lang.reflect.Method;
import java.net.InetAddress;

import junit.framework.Test;
import junit.framework.TestCase;

import org.apache.derbyTesting.junit.BaseTestSuite;

/**
 * Regression coverage for DERBY-7107.
 *
 * NetworkServerControl may bind the server socket to INADDR_ANY, but command
 * sockets must not try to connect to the wildcard address itself.
 */
public final class NetworkServerControlInaddrAnyTest extends TestCase {

    public NetworkServerControlInaddrAnyTest(String name) {
        super(name);
    }

    public void testIpv4WildcardUsesIpv4LoopbackCommandTarget()
        throws Exception
    {
        InetAddress target = commandTargetFor(InetAddress.getByName("0.0.0.0"));

        assertFalse("command target must not remain INADDR_ANY",
            target.isAnyLocalAddress());
        assertEquals("127.0.0.1", target.getHostAddress());
    }

    public void testIpv6WildcardUsesIpv6LoopbackCommandTarget()
        throws Exception
    {
        InetAddress target = commandTargetFor(InetAddress.getByName("::"));

        assertFalse("command target must not remain IPv6 wildcard",
            target.isAnyLocalAddress());
        assertEquals("0:0:0:0:0:0:0:1", target.getHostAddress());
    }

    public void testNonWildcardCommandTargetIsUnchanged()
        throws Exception
    {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        InetAddress target = commandTargetFor(address);

        assertEquals(address, target);
    }

    private static InetAddress commandTargetFor(InetAddress address)
        throws Exception
    {
        Class<?> impl = Class.forName(
            "org.apache.derby.impl.drda.NetworkServerControlImpl");
        Method method = impl.getDeclaredMethod(
            "getCommandTargetAddress", new Class[] { InetAddress.class });
        method.setAccessible(true);
        return (InetAddress) method.invoke(null, new Object[] { address });
    }

    public static Test suite() {
        return new BaseTestSuite(NetworkServerControlInaddrAnyTest.class,
            "NetworkServerControlInaddrAnyTest");
    }
}
