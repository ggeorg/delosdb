/*

   Derby - Class org.apache.derby.iapi.services.context.StoreExecutionContext

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

import org.apache.derby.shared.common.error.StandardException;

/**
 * Narrow store-facing view of the current SQL execution context.
 * <p>
 * This keeps inherited Derby store code from depending directly on
 * LanguageConnectionContext or StatementContext while preserving the small
 * pieces of session/statement information the store historically consumed.
 */
public interface StoreExecutionContext extends Context {
    String CONTEXT_ID = org.apache.derby.shared.common.reference.ContextId.LANG_CONNECTION;

    boolean getRunTimeStatisticsMode();

    boolean databaseVersionAtLeast(int majorVersionId) throws StandardException;

    String getSessionUserId();

    String getStatementText();

    /**
     * Set a session-local override for Derby's uncached row fetch cost.
     * A non-finite value, such as {@link Double#NaN}, means no override.
     */
    void setDelosUncachedRowFetchCostOverride(double cost);

    /**
     * Return the session-local override for Derby's uncached row fetch cost,
     * or {@link Double#NaN} when the session uses Derby's default constant.
     */
    double getDelosUncachedRowFetchCostOverride();
}
