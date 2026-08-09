/*

   Derby - Class org.apache.derby.iapi.sql.compile.StablePlanExecutionRenderer

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

import java.util.HashMap;
import java.util.Map;
import org.apache.derby.iapi.sql.execute.StablePlanExecutionEvidence;

/** Deterministic renderer for a stable plan plus correlated execution evidence. */
public final class StablePlanExecutionRenderer {
    private StablePlanExecutionRenderer() {}

    public static String text(StablePlanModel plan, StablePlanExecutionEvidence evidence) {
        StringBuilder out = new StringBuilder(StablePlanRenderer.text(plan));
        out.append("EXECUTION schemaVersion=").append(evidence.schemaVersion())
                .append(" statementId=").append(evidence.statementId())
                .append(" rootRowsReturned=").append(evidence.rootRowsReturned())
                .append(" nodes=").append(evidence.nodes().size())
                .append(" truncated=").append(evidence.truncated()).append('\n');

        Map<String, Integer> depths = new HashMap<>();
        for (int i = 0; i < evidence.nodes().size(); i++) {
            StablePlanModel.Node planNode = plan.nodes().get(i);
            StablePlanExecutionEvidence.Node node = evidence.nodes().get(i);
            int depth = planNode.parentId() == null
                    ? 0
                    : depths.getOrDefault(planNode.parentId(), -1) + 1;
            depths.put(planNode.id(), depth);
            out.append("  ".repeat(Math.max(0, depth)))
                    .append(node.nodeId())
                    .append(" observed=").append(node.observed());
            if (node.observed()) {
                out.append(" opens=").append(node.opens())
                        .append(" rowsSeen=").append(node.rowsSeen())
                        .append(" rowsFiltered=").append(node.rowsFiltered());
                if (!node.storageMetrics().isEmpty()) {
                    out.append(" storage=[");
                    for (int metric = 0; metric < node.storageMetrics().size(); metric++) {
                        if (metric > 0) out.append(',');
                        StablePlanExecutionEvidence.Metric value = node.storageMetrics().get(metric);
                        out.append(value.name()).append('=').append(value.value());
                    }
                    out.append(']');
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    public static String json(StablePlanModel plan, StablePlanExecutionEvidence evidence) {
        String planJson = StablePlanRenderer.json(plan);
        StringBuilder out = new StringBuilder(planJson.length() + 256);
        out.append("{\"plan\":").append(planJson).append(",\"execution\":{");
        field(out, "schemaVersion", evidence.schemaVersion()).append(',');
        stringField(out, "statementId", evidence.statementId()).append(',');
        field(out, "rootRowsReturned", evidence.rootRowsReturned()).append(',');
        out.append("\"nodes\":[");
        for (int i = 0; i < evidence.nodes().size(); i++) {
            if (i > 0) out.append(',');
            nodeJson(out, evidence.nodes().get(i));
        }
        out.append("],");
        booleanField(out, "truncated", evidence.truncated());
        return out.append("}}").toString();
    }

    private static void nodeJson(StringBuilder out, StablePlanExecutionEvidence.Node node) {
        out.append('{');
        stringField(out, "nodeId", node.nodeId()).append(',');
        booleanField(out, "observed", node.observed()).append(',');
        field(out, "opens", node.opens()).append(',');
        field(out, "rowsSeen", node.rowsSeen()).append(',');
        field(out, "rowsFiltered", node.rowsFiltered()).append(',');
        out.append("\"storageMetrics\":[");
        for (int i = 0; i < node.storageMetrics().size(); i++) {
            if (i > 0) out.append(',');
            StablePlanExecutionEvidence.Metric metric = node.storageMetrics().get(i);
            out.append('{');
            stringField(out, "name", metric.name()).append(',');
            field(out, "value", metric.value());
            out.append('}');
        }
        out.append("]}");
    }

    private static StringBuilder stringField(StringBuilder out, String name, String value) {
        StablePlanRenderer.jsonString(out, name).append(':');
        return value == null ? out.append("null") : StablePlanRenderer.jsonString(out, value);
    }

    private static StringBuilder field(StringBuilder out, String name, long value) {
        return StablePlanRenderer.jsonString(out, name).append(':').append(value);
    }

    private static StringBuilder booleanField(StringBuilder out, String name, boolean value) {
        return StablePlanRenderer.jsonString(out, name).append(':').append(value);
    }
}
