/*

   Derby - Class org.apache.derby.iapi.sql.compile.StablePlanModel

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

package org.apache.derby.iapi.sql.compile;

import java.util.List;

/**
 * Bounded, immutable description of the optimizer plan selected for a prepared
 * statement.
 *
 * <p>The model is diagnostic state only. It does not participate in plan
 * selection, generated-class construction, or execution. Statements without
 * an optimizer-selected result-set tree retain statement metadata with a null
 * root and an empty node list.</p>
 */
public record StablePlanModel(
        int schemaVersion,
        String statementId,
        String statementType,
        String compilationSchema,
        String rootNodeId,
        List<Node> nodes,
        boolean truncated) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public StablePlanModel {
        nodes = List.copyOf(nodes);
    }

    /** Stable plan-node vocabulary independent of compiler implementation classes. */
    public record Node(
            String id,
            String parentId,
            String logicalOperation,
            String physicalOperation,
            String relation,
            String storageMode,
            String accessPath,
            String joinStrategy,
            Double estimatedRows,
            Double estimatedCost,
            List<String> predicates,
            List<String> ordering,
            String decisionReason) {

        public Node {
            predicates = List.copyOf(predicates);
            ordering = List.copyOf(ordering);
        }
    }
}
