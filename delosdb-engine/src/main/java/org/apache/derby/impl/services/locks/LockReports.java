/*

   Derby - Class org.apache.derby.impl.services.locks.LockReports

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

package org.apache.derby.impl.services.locks;

import java.util.ServiceLoader;

import org.apache.derby.iapi.services.locks.LockReportSupport;
import org.apache.derby.shared.common.reference.ContextId;

/**
 * Finds the language connection context and the engine's lock report support
 * for timeout and deadlock reports.
 */
final class LockReports {
    private static volatile LockReportSupport support;

    private LockReports() {
    }

    /** @return the current language connection context, or null */
    static Object currentLanguageConnectionContext() {
        return Deadlock.getContext(ContextId.LANG_CONNECTION);
    }

    /** @return the engine's support, or null if no provider is present */
    static LockReportSupport support() {
        LockReportSupport current = support;
        if (current != null) {
            return current;
        }
        current = ServiceLoader.load(LockReportSupport.class)
                .findFirst()
                .orElseGet(LockReports::loadClasspathSupportOrNull);
        support = current;
        return current;
    }

    private static LockReportSupport loadClasspathSupportOrNull() {
        try {
            Class<?> providerClass = Class.forName(
                    "org.apache.derby.impl.sql.conn.EngineLockReportSupport");
            return (LockReportSupport) providerClass
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
