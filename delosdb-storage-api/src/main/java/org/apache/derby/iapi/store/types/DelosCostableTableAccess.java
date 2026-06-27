/*

   Derby - Class org.apache.derby.iapi.store.types.DelosCostableTableAccess

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
package org.apache.derby.iapi.store.types;

/**
 * Optional store-neutral costing surface for table access implementations.
 *
 * <p>H1 deliberately keeps costing separate from scan and mutation surfaces.
 * Implementing this interface means a table access object can expose provider
 * statistics and a coarse full-scan estimate.  It does not by itself route that
 * estimate into Derby's optimizer or change any access-path decision.</p>
 */
public interface DelosCostableTableAccess extends DelosTableAccess {
    /** Provider-backed table statistics and coarse full-scan estimate. */
    DelosTableCostEstimate estimateTableCost(DelosAccessContext context);
}
