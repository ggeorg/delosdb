/*

   Derby - Class org.apache.derby.iapi.services.context.ContextKernelSupport

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

package org.apache.derby.iapi.services.context;

import java.util.Locale;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.stream.HeaderPrintWriter;

/**
 * Small engine-owned support seam for the inherited Derby context service.
 *
 * <p>The context package is thread/session-global infrastructure needed by the
 * legacy store. It must not pull the full Derby monitor, property system, or
 * SQL-facing security package into lower-level contracts. The engine installs
 * the real implementation from its monitor package; the fallback exists only so
 * isolated tests and compile-time proofs can construct context services without
 * booting the full engine.</p>
 */
public interface ContextKernelSupport
{
    HeaderPrintWriter getErrorStream();

    Locale getLocaleFromString(String localeID) throws StandardException;

    int getSystemInt(String key, int min, int max, int defaultValue);

    void shutdownSystem();
}
