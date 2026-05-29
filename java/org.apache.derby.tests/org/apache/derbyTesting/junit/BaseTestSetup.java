/*
 *
 * Derby - Class BaseTestSetup
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 * either express or implied. See the License for the specific 
 * language governing permissions and limitations under the License.
 */
package org.apache.derbyTesting.junit;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestResult;

/**
 * TestSetup/Decorator base class for Derby's JUnit
 * tests. Installs the security manager according
 * to the configuration before executing any setup
 * or tests. Matches the security manager setup
 * provided by BaseTestCase.
 *
 */
public abstract class BaseTestSetup extends TestSetup {
    
    protected BaseTestSetup(Test test) {
        super(test);
    }

    /**
     * This method used to setup the security manager. Now it just runs the test.
     */
    public void run(TestResult result)
    {
        super.run(result);
    }


}
