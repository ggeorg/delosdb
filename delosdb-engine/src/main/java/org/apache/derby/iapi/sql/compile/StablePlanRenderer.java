/*

   Derby - Class org.apache.derby.iapi.sql.compile.StablePlanRenderer

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
import java.util.List;
import java.util.Map;

/** Deterministic text and JSON renderers for {@link StablePlanModel}. */
public final class StablePlanRenderer {
    private StablePlanRenderer() {}

    public static String text(StablePlanModel model) {
        StringBuilder out = new StringBuilder(256 + model.nodes().size() * 128);
        out.append("PLAN schemaVersion=").append(model.schemaVersion())
                .append(" statementId=").append(model.statementId())
                .append(" statementType=").append(value(model.statementType()))
                .append(" schema=").append(value(model.compilationSchema()))
                .append(" root=").append(value(model.rootNodeId()))
                .append(" nodes=").append(model.nodes().size())
                .append(" truncated=").append(model.truncated()).append('\n');

        Map<String, Integer> depths = new HashMap<>();
        for (StablePlanModel.Node node : model.nodes()) {
            int depth = node.parentId() == null ? 0 : depths.getOrDefault(node.parentId(), -1) + 1;
            depths.put(node.id(), depth);
            out.append("  ".repeat(Math.max(0, depth)))
                    .append(node.id()).append(' ')
                    .append(node.logicalOperation()).append('/').append(node.physicalOperation());
            field(out, "relation", node.relation());
            field(out, "storage", node.storageMode());
            field(out, "access", node.accessPath());
            field(out, "join", node.joinStrategy());
            number(out, "rows", node.estimatedRows());
            number(out, "cost", node.estimatedCost());
            field(out, "reason", node.decisionReason());
            list(out, "predicates", node.predicates());
            list(out, "ordering", node.ordering());
            out.append('\n');
        }
        return out.toString();
    }

    public static String json(StablePlanModel model) {
        StringBuilder out = new StringBuilder(256 + model.nodes().size() * 192);
        out.append('{');
        jsonField(out, "schemaVersion", model.schemaVersion()).append(',');
        jsonField(out, "statementId", model.statementId()).append(',');
        jsonField(out, "statementType", model.statementType()).append(',');
        jsonField(out, "compilationSchema", model.compilationSchema()).append(',');
        jsonField(out, "rootNodeId", model.rootNodeId()).append(',');
        out.append("\"nodes\":[");
        for (int i = 0; i < model.nodes().size(); i++) {
            if (i > 0) out.append(',');
            nodeJson(out, model.nodes().get(i));
        }
        out.append("],");
        jsonField(out, "truncated", model.truncated());
        return out.append('}').toString();
    }

    private static void nodeJson(StringBuilder out, StablePlanModel.Node node) {
        out.append('{');
        jsonField(out, "id", node.id()).append(',');
        jsonField(out, "parentId", node.parentId()).append(',');
        jsonField(out, "logicalOperation", node.logicalOperation()).append(',');
        jsonField(out, "physicalOperation", node.physicalOperation()).append(',');
        jsonField(out, "relation", node.relation()).append(',');
        jsonField(out, "storageMode", node.storageMode()).append(',');
        jsonField(out, "accessPath", node.accessPath()).append(',');
        jsonField(out, "joinStrategy", node.joinStrategy()).append(',');
        jsonField(out, "estimatedRows", node.estimatedRows()).append(',');
        jsonField(out, "estimatedCost", node.estimatedCost()).append(',');
        jsonList(out, "predicates", node.predicates()).append(',');
        jsonList(out, "ordering", node.ordering()).append(',');
        jsonField(out, "decisionReason", node.decisionReason());
        out.append('}');
    }

    private static void field(StringBuilder out, String name, String value) {
        if (value != null) out.append(' ').append(name).append('=').append(value);
    }

    private static void number(StringBuilder out, String name, Double value) {
        if (value != null) out.append(' ').append(name).append('=').append(value);
    }

    private static void list(StringBuilder out, String name, List<String> values) {
        if (!values.isEmpty()) out.append(' ').append(name).append('=').append(values);
    }

    private static String value(String value) {
        return value == null ? "-" : value;
    }

    private static StringBuilder jsonField(StringBuilder out, String name, String value) {
        jsonString(out, name).append(':');
        return value == null ? out.append("null") : jsonString(out, value);
    }

    private static StringBuilder jsonField(StringBuilder out, String name, int value) {
        return jsonString(out, name).append(':').append(value);
    }

    private static StringBuilder jsonField(StringBuilder out, String name, boolean value) {
        return jsonString(out, name).append(':').append(value);
    }

    private static StringBuilder jsonField(StringBuilder out, String name, Double value) {
        jsonString(out, name).append(':');
        return value == null || !Double.isFinite(value) ? out.append("null") : out.append(value);
    }

    private static StringBuilder jsonList(StringBuilder out, String name, List<String> values) {
        jsonString(out, name).append(':').append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            jsonString(out, values.get(i));
        }
        return out.append(']');
    }

    private static StringBuilder jsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"');
    }
}
