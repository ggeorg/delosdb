/*

   Derby - Class org.apache.derby.iapi.sql.execute.StablePlanExecutionEvidence

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

package org.apache.derby.iapi.sql.execute;

import java.util.List;

/** Bounded immutable execution evidence correlated with stable plan-node ids. */
public record StablePlanExecutionEvidence(
        int schemaVersion,
        String statementId,
        long rootRowsReturned,
        List<Node> nodes,
        boolean truncated) {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    public StablePlanExecutionEvidence {
        nodes = List.copyOf(nodes);
    }

    /** Runtime evidence for one stable plan node; actualRows is operator output rows. */
    public record Node(
            String nodeId,
            boolean observed,
            int opens,
            Long actualRows,
            long rowsSeen,
            long rowsFiltered,
            long elapsedMillis,
            long openMillis,
            long nextMillis,
            long closeMillis,
            List<Metric> storageMetrics) {

        public Node {
            storageMetrics = List.copyOf(storageMetrics);
        }
    }

    /** Stable numeric storage metric captured at the authoritative scan boundary. */
    public record Metric(String name, long value) {}
}
