/*

   Derby - Class org.apache.derby.impl.sql.compile.StablePlanModelBuilder

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

package org.apache.derby.impl.sql.compile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.derby.iapi.sql.compile.AccessPath;
import org.apache.derby.iapi.sql.compile.CostEstimate;
import org.apache.derby.iapi.sql.compile.JoinStrategy;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.dictionary.ConglomerateDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;

/** Builds the stable diagnostic model from Derby's already-selected plan. */
final class StablePlanModelBuilder {
    private static final int MAX_NODES = 512;

    private final List<StablePlanModel.Node> nodes = new ArrayList<>();
    private final IdentityHashMap<ResultSetNode, String> seen = new IdentityHashMap<>();
    private boolean truncated;

    static StablePlanModel build(
            StatementNode statement, String sourceText, String compilationSchema) {
        String schema = compilationSchema == null ? "" : compilationSchema;
        String source = sourceText == null ? "" : sourceText;
        String statementType = statement.statementToString().toUpperCase(Locale.ROOT);
        String statementId = UUID.nameUUIDFromBytes(
                (schema + '\u0000' + source).getBytes(StandardCharsets.UTF_8)).toString();

        StablePlanModelBuilder builder = new StablePlanModelBuilder();
        ResultSetNode root = statement instanceof DMLStatementNode dml ? dml.resultSet : null;
        String rootNodeId = root == null ? null : builder.visit(root, null);

        return new StablePlanModel(
                StablePlanModel.CURRENT_SCHEMA_VERSION,
                statementId,
                statementType,
                schema,
                rootNodeId,
                builder.nodes,
                builder.truncated);
    }

    private String visit(ResultSetNode node, String parentId) {
        if (node == null) {
            return null;
        }

        String existing = seen.get(node);
        if (existing != null) {
            return existing;
        }
        if (nodes.size() >= MAX_NODES) {
            truncated = true;
            return null;
        }

        String id = "n" + nodes.size();
        seen.put(node, id);
        nodes.add(describe(node, id, parentId));

        if (node instanceof IndexToBaseRowNode indexToBaseRow) {
            visit(indexToBaseRow.source, id);
        } else if (node instanceof SingleChildResultSetNode singleChild) {
            visit(singleChild.childResult, id);
        } else if (node instanceof TableOperatorNode operator) {
            visit(operator.leftResultSet, id);
            visit(operator.rightResultSet, id);
        }
        return id;
    }

    private StablePlanModel.Node describe(ResultSetNode node, String id, String parentId) {
        FromBaseTable baseTable = baseTable(node);
        AccessPath accessPath = accessPath(node, baseTable);
        ConglomerateDescriptor conglomerate =
                accessPath == null ? null : accessPath.getConglomerateDescriptor();
        CostEstimate cost = node.getCostEstimate();
        if (cost != null && cost.isUninitialized()) {
            cost = null;
        }

        Operation operation = operation(node, conglomerate);
        return new StablePlanModel.Node(
                id,
                parentId,
                operation.logical(),
                operation.physical(),
                relation(baseTable),
                storageMode(baseTable),
                accessPath(conglomerate),
                joinStrategy(node, accessPath),
                cost == null ? null : cost.rowCount(),
                cost == null ? null : cost.getEstimatedCost(),
                List.of(),
                List.of(),
                null);
    }

    private static FromBaseTable baseTable(ResultSetNode node) {
        if (node instanceof FromBaseTable baseTable) {
            return baseTable;
        }
        if (node instanceof IndexToBaseRowNode indexToBaseRow) {
            return indexToBaseRow.source;
        }
        return null;
    }

    private static AccessPath accessPath(ResultSetNode node, FromBaseTable baseTable) {
        if (baseTable != null) {
            return baseTable.getTrulyTheBestAccessPath();
        }
        if (node instanceof FromTable fromTable) {
            return fromTable.getTrulyTheBestAccessPath();
        }
        return null;
    }

    private static String relation(FromBaseTable baseTable) {
        TableDescriptor table = baseTable == null ? null : baseTable.getTableDescriptor();
        return table == null ? null : table.getQualifiedName();
    }

    private static String storageMode(FromBaseTable baseTable) {
        TableDescriptor table = baseTable == null ? null : baseTable.getTableDescriptor();
        return table == null ? null : table.getStorageProviderName();
    }

    private static String accessPath(ConglomerateDescriptor conglomerate) {
        if (conglomerate == null) {
            return null;
        }
        return conglomerate.isIndex() ? conglomerate.getConglomerateName() : "BASE_TABLE";
    }

    private static String joinStrategy(ResultSetNode node, AccessPath accessPath) {
        JoinStrategy strategy = accessPath == null ? null : accessPath.getJoinStrategy();
        if (strategy == null && node instanceof TableOperatorNode operator
                && operator.rightResultSet instanceof FromTable right) {
            AccessPath rightPath = right.getTrulyTheBestAccessPath();
            strategy = rightPath == null ? null : rightPath.getJoinStrategy();
        }
        return strategy == null ? null : normalize(strategy.getName());
    }

    private static Operation operation(
            ResultSetNode node, ConglomerateDescriptor conglomerate) {
        if (node instanceof IndexToBaseRowNode) return Operation.INDEX_TO_BASE_ROW;
        if (node instanceof FromBaseTable) {
            if (conglomerate == null) return Operation.SCAN;
            return conglomerate.isIndex() ? Operation.INDEX_SCAN : Operation.TABLE_SCAN;
        }
        if (node instanceof HalfOuterJoinNode) return Operation.OUTER_JOIN;
        if (node instanceof JoinNode) return Operation.JOIN;
        if (node instanceof ProjectRestrictNode) return Operation.PROJECT_RESTRICT;
        if (node instanceof GroupByNode) return Operation.GROUP_BY;
        if (node instanceof DistinctNode) return Operation.DISTINCT;
        if (node instanceof OrderByNode) return Operation.ORDER_BY;
        if (node instanceof RowCountNode) return Operation.ROW_COUNT;
        if (node instanceof UnionNode) return Operation.UNION;
        if (node instanceof IntersectOrExceptNode) return Operation.INTERSECT_OR_EXCEPT;
        if (node instanceof RowResultSetNode) return Operation.ROW_VALUES;
        if (node instanceof WindowResultSetNode) return Operation.WINDOW;
        if (node instanceof HashTableNode) return Operation.HASH_TABLE;
        if (node instanceof MaterializeResultSetNode) return Operation.MATERIALIZE;
        if (node instanceof MaterializeSubqueryNode) return Operation.MATERIALIZED_SUBQUERY;
        if (node instanceof NormalizeResultSetNode) return Operation.NORMALIZE;
        if (node instanceof ScrollInsensitiveResultSetNode) return Operation.SCROLL_INSENSITIVE;
        if (node instanceof FromVTI) return Operation.VTI_SCAN;
        if (node instanceof CurrentOfNode) return Operation.CURRENT_OF;
        if (node instanceof FromSubquery) return Operation.SUBQUERY;
        return Operation.GENERIC;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private enum Operation {
        INDEX_TO_BASE_ROW("SCAN", "INDEX_TO_BASE_ROW"),
        INDEX_SCAN("SCAN", "INDEX_SCAN"),
        TABLE_SCAN("SCAN", "TABLE_SCAN"),
        SCAN("SCAN", "SCAN"),
        OUTER_JOIN("JOIN", "OUTER_JOIN"),
        JOIN("JOIN", "JOIN"),
        PROJECT_RESTRICT("PROJECT_FILTER", "PROJECT_RESTRICT"),
        GROUP_BY("AGGREGATE", "GROUP_BY"),
        DISTINCT("DISTINCT", "DISTINCT"),
        ORDER_BY("SORT", "ORDER_BY"),
        ROW_COUNT("LIMIT", "ROW_COUNT"),
        UNION("SET_OPERATION", "UNION"),
        INTERSECT_OR_EXCEPT("SET_OPERATION", "INTERSECT_OR_EXCEPT"),
        ROW_VALUES("VALUES", "ROW_VALUES"),
        WINDOW("WINDOW", "WINDOW"),
        HASH_TABLE("MATERIALIZE", "HASH_TABLE"),
        MATERIALIZE("MATERIALIZE", "MATERIALIZE"),
        MATERIALIZED_SUBQUERY("MATERIALIZE", "MATERIALIZED_SUBQUERY"),
        NORMALIZE("NORMALIZE", "NORMALIZE"),
        SCROLL_INSENSITIVE("SCROLL", "SCROLL_INSENSITIVE"),
        VTI_SCAN("SCAN", "VTI_SCAN"),
        CURRENT_OF("SCAN", "CURRENT_OF"),
        SUBQUERY("SUBQUERY", "SUBQUERY"),
        GENERIC("RESULT_SET", "GENERIC");

        private final String logical;
        private final String physical;

        Operation(String logical, String physical) {
            this.logical = logical;
            this.physical = physical;
        }

        String logical() { return logical; }
        String physical() { return physical; }
    }
}
