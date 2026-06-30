/*

   Derby - Class org.apache.derby.impl.drda.DrdaThreading

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

package org.apache.derby.impl.drda;


/**
 * Centralizes the DRDA server thread creation policy.
 *
 * <p>The default remains the inherited platform-thread behavior. Virtual
 * connection workers are opt-in so the compatibility behavior of the DRDA
 * network server does not change unless the DelosDB-specific property is set.
 * </p>
 */
final class DrdaThreading {
    static final String THREAD_MODE_PROPERTY =
            DrdaServerConfiguration.THREAD_MODE_PROPERTY;
    static final String THREAD_MODE_PLATFORM =
            DrdaServerConfiguration.THREAD_MODE_PLATFORM;
    static final String THREAD_MODE_VIRTUAL =
            DrdaServerConfiguration.THREAD_MODE_VIRTUAL;

    private final DrdaServerConfiguration.ThreadMode mode;

    private DrdaThreading(DrdaServerConfiguration.ThreadMode mode) {
        this.mode = mode;
    }

    static DrdaThreading fromSystemProperties() {
        return fromConfiguration(
                DrdaServerConfiguration.fromSystemProperties());
    }

    static DrdaThreading platformForTesting() {
        return new DrdaThreading(
                DrdaServerConfiguration.ThreadMode.PLATFORM);
    }

    static DrdaThreading virtualForTesting() {
        return new DrdaThreading(
                DrdaServerConfiguration.ThreadMode.VIRTUAL);
    }

    static DrdaThreading fromPropertyValueForTesting(String value) {
        return new DrdaThreading(
                DrdaServerConfiguration.parseThreadModeForTesting(value));
    }

    static DrdaThreading fromConfiguration(
            DrdaServerConfiguration configuration) {
        return new DrdaThreading(configuration.threadMode());
    }

    Thread newConnectionWorkerThread(DRDAConnThread worker) {
        if (usesVirtualConnectionWorkers()) {
            return Thread.ofVirtual().name(worker.getName()).unstarted(worker);
        }

        return worker;
    }

    Thread startThreadForTesting(String name, Runnable task) {
        if (usesVirtualConnectionWorkers()) {
            return Thread.ofVirtual().name(name).start(task);
        }

        Thread thread = new Thread(task, name);
        thread.start();
        return thread;
    }

    boolean usesVirtualConnectionWorkers() {
        return mode == DrdaServerConfiguration.ThreadMode.VIRTUAL;
    }

    String modeName() {
        return usesVirtualConnectionWorkers()
                ? THREAD_MODE_VIRTUAL
                : THREAD_MODE_PLATFORM;
    }
}
