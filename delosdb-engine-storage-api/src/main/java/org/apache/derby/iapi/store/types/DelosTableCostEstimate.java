/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableCostEstimate

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

/** Store-neutral table statistics and coarse cost estimate for H-phase proofs. */
public record DelosTableCostEstimate(
        long logicalRowCount,
        long visibleRowCount,
        long physicalVersionCount,
        long deadVersionEstimate,
        long estimatedFullScanCost) {
    public DelosTableCostEstimate {
        if (logicalRowCount < 0
                || visibleRowCount < 0
                || physicalVersionCount < 0
                || deadVersionEstimate < 0
                || estimatedFullScanCost < 0) {
            throw new IllegalArgumentException("Delos table cost estimate values must be non-negative");
        }
    }
}
