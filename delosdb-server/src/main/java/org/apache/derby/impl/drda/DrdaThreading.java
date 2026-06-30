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

import java.util.Locale;
import org.apache.derby.iapi.services.property.PropertyUtil;

/**
 * Centralizes the DRDA server thread creation policy.
 *
 * <p>The default remains the inherited platform-thread behavior. Virtual
 * connection workers are opt-in so the compatibility behavior of the DRDA
 * network server does not change unless the DelosDB-specific property is set.
 * </p>
 */
final class DrdaThreading {
    static final String THREAD_MODE_PROPERTY = "delos.drda.threadMode";
    static final String THREAD_MODE_PLATFORM = "platform";
    static final String THREAD_MODE_VIRTUAL = "virtual";

    private enum Mode {
        PLATFORM,
        VIRTUAL
    }

    private final Mode mode;

    private DrdaThreading(Mode mode) {
        this.mode = mode;
    }

    static DrdaThreading fromSystemProperties() {
        return fromPropertyValue(PropertyUtil.getSystemProperty(
                THREAD_MODE_PROPERTY, THREAD_MODE_PLATFORM));
    }

    static DrdaThreading platformForTesting() {
        return new DrdaThreading(Mode.PLATFORM);
    }

    static DrdaThreading virtualForTesting() {
        return new DrdaThreading(Mode.VIRTUAL);
    }

    static DrdaThreading fromPropertyValueForTesting(String value) {
        return fromPropertyValue(value);
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
        return mode == Mode.VIRTUAL;
    }

    String modeName() {
        return usesVirtualConnectionWorkers()
                ? THREAD_MODE_VIRTUAL
                : THREAD_MODE_PLATFORM;
    }

    private static DrdaThreading fromPropertyValue(String value) {
        if (value == null) {
            return platformForTesting();
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (THREAD_MODE_VIRTUAL.equals(normalized)) {
            return virtualForTesting();
        }

        return platformForTesting();
    }
}
