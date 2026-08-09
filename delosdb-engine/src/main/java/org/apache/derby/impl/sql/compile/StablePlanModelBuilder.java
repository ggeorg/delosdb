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
import java.util.Properties;
import java.util.UUID;
import org.apache.derby.iapi.sql.compile.AccessPath;
import org.apache.derby.iapi.sql.compile.CostEstimate;
import org.apache.derby.iapi.sql.compile.JoinStrategy;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.dictionary.ConglomerateDescriptor;
import org.apache.derby.iapi.sql.dictionary.IndexRowGenerator;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.types.DataValueDescriptor;

/** Builds the stable diagnostic model from Derby's already-selected plan. */
final class StablePlanModelBuilder {
    private static final int MAX_NODES = 512;
    private static final int MAX_PREDICATES = 64;
    private static final int MAX_ORDER_COLUMNS = 32;
    private static final int MAX_LIST_VALUES = 8;
    private static final int MAX_EXPRESSION_DEPTH = 12;

    private final List<StablePlanModel.Node> nodes = new ArrayList<>();
    private final IdentityHashMap<ResultSetNode, String> seen = new IdentityHashMap<>();
    private final List<Integer> resultSetNumbers;
    private final boolean describeNodes;
    private int nodeCount;
    private boolean truncated;

    private StablePlanModelBuilder(boolean describeNodes, boolean captureResultSetNumbers) {
        this.describeNodes = describeNodes;
        resultSetNumbers = captureResultSetNumbers ? new ArrayList<>() : null;
    }

    static StablePlanModel build(
            StatementNode statement, String sourceText, String compilationSchema) {
        String schema = compilationSchema == null ? "" : compilationSchema;
        String source = sourceText == null ? "" : sourceText;
        String statementType = statement.statementToString().toUpperCase(Locale.ROOT);
        String statementId = UUID.nameUUIDFromBytes(
                (schema + '\u0000' + source).getBytes(StandardCharsets.UTF_8)).toString();

        StablePlanModelBuilder builder = new StablePlanModelBuilder(true, false);
        ResultSetNode root = root(statement);
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

    static int[] resultSetNumbers(StatementNode statement) {
        StablePlanModelBuilder builder = new StablePlanModelBuilder(false, true);
        builder.visit(root(statement), null);
        int[] result = new int[builder.resultSetNumbers.size()];
        for (int i = 0; i < result.length; i++) result[i] = builder.resultSetNumbers.get(i);
        return result;
    }

    private static ResultSetNode root(StatementNode statement) {
        return statement instanceof DMLStatementNode dml ? dml.resultSet : null;
    }

    private String visit(ResultSetNode node, String parentId) {
        if (node == null) {
            return null;
        }

        String existing = seen.get(node);
        if (existing != null) {
            return existing;
        }
        if (nodeCount >= MAX_NODES) {
            truncated = true;
            return null;
        }

        String id = "n" + nodeCount++;
        seen.put(node, id);
        if (describeNodes) nodes.add(describe(node, id, parentId));
        if (resultSetNumbers != null) {
            resultSetNumbers.add(node.hasAssignedResultSetNumber() ? node.getResultSetNumber() : -1);
        }

        if (node instanceof IndexToBaseRowNode indexToBaseRow) {
            visit(indexToBaseRow.source, id);
        } else if (node instanceof SingleChildResultSetNode singleChild) {
            visit(singleChild.childResult, id);
        } else if (node instanceof TableOperatorNode operator) {
            visit(operator.leftResultSet, id);
            visit(operator.rightResultSet, id);
        } else if (node instanceof FromSubquery fromSubquery) {
            visit(fromSubquery.getSubquery(), id);
        } else if (node instanceof SelectNode select) {
            for (ResultSetNode child : select.getFromList()) {
                visit(child, id);
            }
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
                predicates(node),
                ordering(node, baseTable, conglomerate),
                decisionReason(node, conglomerate));
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

    private static List<String> predicates(ResultSetNode node) {
        List<String> result = new ArrayList<>();
        if (node instanceof FromBaseTable baseTable) {
            appendPredicates(result, "STORE", baseTable.storeRestrictionList);
            appendPredicates(result, "RESIDUAL", baseTable.nonStoreRestrictionList);
            appendPredicates(result, "REQUALIFY", baseTable.requalificationRestrictionList);
        } else if (node instanceof ProjectRestrictNode projectRestrict) {
            appendPredicates(result, "FILTER", projectRestrict.restrictionList);
        }
        return result.isEmpty() ? List.of() : result;
    }

    private static void appendPredicates(
            List<String> result, String placement, PredicateList predicates) {
        if (predicates == null || predicates.size() == 0) {
            return;
        }
        for (Predicate predicate : predicates) {
            if (result.size() >= MAX_PREDICATES) {
                result.add("PREDICATES_TRUNCATED");
                return;
            }
            StringBuilder label = new StringBuilder(placement);
            if (predicate.isStartKey()) label.append("|START");
            if (predicate.isStopKey()) label.append("|STOP");
            if (predicate.isQualifier()) label.append("|QUALIFIER");
            result.add(label.append(':')
                    .append(expression(predicate.getAndNode().getLeftOperand(), 0))
                    .toString());
        }
    }

    private static List<String> ordering(
            ResultSetNode node, FromBaseTable baseTable, ConglomerateDescriptor conglomerate) {
        if (node instanceof OrderByNode orderBy) {
            List<String> result = new ArrayList<>();
            int count = 0;
            for (OrderByColumn column : orderBy.orderByList) {
                if (count++ >= MAX_ORDER_COLUMNS) {
                    result.add("ORDERING_TRUNCATED");
                    break;
                }
                result.add("ORDER_BY:"
                        + expression(column.getNonRedundantExpression(), 0)
                        + ':' + (column.isAscending() ? "ASC" : "DESC")
                        + ':' + (column.isNullsOrderedLow() ? "NULLS_LOW" : "NULLS_HIGH"));
            }
            return result;
        }
        if (!(node instanceof FromBaseTable)
                || baseTable == null
                || conglomerate == null
                || !conglomerate.isIndex()) {
            return List.of();
        }

        TableDescriptor table = baseTable.getTableDescriptor();
        IndexRowGenerator index = conglomerate.getIndexDescriptor();
        int[] positions = index.baseColumnPositions();
        boolean[] ascending = index.isAscending();
        List<String> result = new ArrayList<>(Math.min(positions.length, MAX_ORDER_COLUMNS));
        for (int i = 0; i < positions.length && i < MAX_ORDER_COLUMNS; i++) {
            String column = table == null || table.getColumnDescriptor(positions[i]) == null
                    ? "#" + positions[i]
                    : table.getColumnDescriptor(positions[i]).getColumnName();
            result.add("INDEX:COLUMN(" + column + "):" + (ascending[i] ? "ASC" : "DESC"));
        }
        if (positions.length > MAX_ORDER_COLUMNS) {
            result.add("ORDERING_TRUNCATED");
        }
        return result;
    }

    private static String decisionReason(
            ResultSetNode node, ConglomerateDescriptor conglomerate) {
        if (node instanceof IndexToBaseRowNode) {
            return "INDEX_NOT_COVERING";
        }
        if (node instanceof OrderByNode) {
            return "SORT_REQUIRED";
        }
        if (node instanceof ProjectRestrictNode projectRestrict
                && projectRestrict.restrictionList != null
                && projectRestrict.restrictionList.size() != 0) {
            return "RESIDUAL_FILTER_REQUIRED";
        }
        if (node instanceof JoinNode join) {
            if (join.rightResultSet instanceof FromTable right
                    && right.getUserSpecifiedJoinStrategy() != null) {
                return "FORCED_JOIN_STRATEGY";
            }
            return "COST_SELECTED_JOIN_STRATEGY";
        }
        if (!(node instanceof FromBaseTable baseTable)) {
            return operation(node, conglomerate) == Operation.GENERIC
                    ? "UNCLASSIFIED_RESULT_SET"
                    : null;
        }

        Properties properties = baseTable.getProperties();
        String forcedIndex = properties == null ? null : properties.getProperty("index");
        String accessReason;
        if (forcedIndex != null) {
            accessReason = "NULL".equalsIgnoreCase(forcedIndex)
                    ? "FORCED_TABLE_SCAN"
                    : "FORCED_INDEX";
        } else if (conglomerate != null) {
            accessReason = conglomerate.isIndex()
                    ? "COST_SELECTED_INDEX"
                    : "COST_SELECTED_TABLE_SCAN";
        } else {
            accessReason = null;
        }
        return combineReasons(
                accessReason,
                baseTable.getUserSpecifiedJoinStrategy() == null
                        ? null
                        : "FORCED_JOIN_STRATEGY");
    }

    private static String combineReasons(String first, String second) {
        if (first == null) return second;
        if (second == null) return first;
        return first + '+' + second;
    }

    private static String expression(ValueNode node, int depth) {
        if (node == null) {
            return "EXPRESSION";
        }
        if (depth >= MAX_EXPRESSION_DEPTH) {
            return "EXPRESSION";
        }
        if (node instanceof ColumnReference column) {
            String table = column.getTableName();
            String name = column.getColumnName();
            return "COLUMN(" + (table == null || table.isBlank() ? name : table + '.' + name) + ')';
        }
        if (node instanceof VirtualColumnNode virtualColumn) {
            return expression(virtualColumn.getSourceColumn().getExpression(), depth + 1);
        }
        if (node instanceof ParameterNode parameter) {
            return "PARAMETER(" + (parameter.getParameterNumber() + 1) + ')';
        }
        if (node instanceof ConstantNode constant) {
            DataValueDescriptor value = constant.getValue();
            String type = value == null ? "UNKNOWN" : normalize(value.getTypeName());
            return (constant.isNull() ? "NULL(" : "LITERAL(") + type + ')';
        }
        if (node instanceof BinaryRelationalOperatorNode relational) {
            if (relational.isInListProbeNode()) {
                return expression(relational.getInListOp(), depth + 1);
            }
            return binaryExpression(
                    relational.leftOperand,
                    relational.operator,
                    relational.rightOperand,
                    depth);
        }
        if (node instanceof IsNullNode isNull) {
            return expression(isNull.getOperand(), depth + 1)
                    + (isNull.getOperator() == RelationalOperator.IS_NULL_RELOP
                            ? " IS NULL"
                            : " IS NOT NULL");
        }
        if (node instanceof InListOperatorNode inList) {
            StringBuilder result = new StringBuilder(expression(inList.leftOperand, depth + 1))
                    .append(" IN (");
            int size = inList.rightOperandList.size();
            for (int i = 0; i < size && i < MAX_LIST_VALUES; i++) {
                if (i > 0) result.append(',');
                result.append(expression(inList.rightOperandList.elementAt(i), depth + 1));
            }
            if (size > MAX_LIST_VALUES) result.append(",...");
            return result.append(')').toString();
        }
        if (node instanceof BinaryOperatorNode binary && stableBinaryOperator(binary.operator)) {
            return binaryExpression(binary.leftOperand, binary.operator, binary.rightOperand, depth);
        }
        if (node instanceof UnaryOperatorNode unary && stableUnaryOperator(unary.operator)) {
            return normalize(unary.operator) + '(' + expression(unary.getOperand(), depth + 1) + ')';
        }
        return "EXPRESSION";
    }

    private static String binaryExpression(
            ValueNode left, String operator, ValueNode right, int depth) {
        return expression(left, depth + 1)
                + ' ' + normalize(operator) + ' '
                + expression(right, depth + 1);
    }

    private static boolean stableBinaryOperator(String operator) {
        if (operator == null) return false;
        return switch (normalize(operator)) {
            case "+", "-", "*", "/", "||", "AND", "OR", "LIKE" -> true;
            default -> false;
        };
    }

    private static boolean stableUnaryOperator(String operator) {
        if (operator == null) return false;
        return switch (normalize(operator)) {
            case "+", "-", "NOT" -> true;
            default -> false;
        };
    }

    private static Operation operation(
            ResultSetNode node, ConglomerateDescriptor conglomerate) {
        if (node instanceof FromBaseTable baseTable) {
            if (baseTable.isDistinctScan()) return Operation.DISTINCT_SCAN;
            if (conglomerate == null) return Operation.SCAN;
            return conglomerate.isIndex() ? Operation.INDEX_SCAN : Operation.TABLE_SCAN;
        }
        return Operation.forNode(node);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private enum Operation {
        INDEX_TO_BASE_ROW("SCAN", "INDEX_TO_BASE_ROW", IndexToBaseRowNode.class),
        DISTINCT_SCAN("DISTINCT", "DISTINCT_SCAN"),
        INDEX_SCAN("SCAN", "INDEX_SCAN"),
        TABLE_SCAN("SCAN", "TABLE_SCAN"),
        SCAN("SCAN", "SCAN"),
        OUTER_JOIN("JOIN", "OUTER_JOIN", HalfOuterJoinNode.class),
        JOIN("JOIN", "JOIN", JoinNode.class),
        PROJECT_RESTRICT("PROJECT_FILTER", "PROJECT_RESTRICT", ProjectRestrictNode.class),
        GROUP_BY("AGGREGATE", "GROUP_BY", GroupByNode.class),
        DISTINCT("DISTINCT", "DISTINCT", DistinctNode.class),
        ORDER_BY("SORT", "ORDER_BY", OrderByNode.class),
        ROW_COUNT("LIMIT", "ROW_COUNT", RowCountNode.class),
        UNION("SET_OPERATION", "UNION", UnionNode.class),
        INTERSECT_OR_EXCEPT("SET_OPERATION", "INTERSECT_OR_EXCEPT", IntersectOrExceptNode.class),
        ROW_VALUES("VALUES", "ROW_VALUES", RowResultSetNode.class),
        WINDOW("WINDOW", "WINDOW", WindowResultSetNode.class),
        HASH_TABLE("MATERIALIZE", "HASH_TABLE", HashTableNode.class),
        MATERIALIZE("MATERIALIZE", "MATERIALIZE", MaterializeResultSetNode.class),
        MATERIALIZED_SUBQUERY("MATERIALIZE", "MATERIALIZED_SUBQUERY", MaterializeSubqueryNode.class),
        NORMALIZE("NORMALIZE", "NORMALIZE", NormalizeResultSetNode.class),
        SCROLL_INSENSITIVE("SCROLL", "SCROLL_INSENSITIVE", ScrollInsensitiveResultSetNode.class),
        VTI_SCAN("SCAN", "VTI_SCAN", FromVTI.class),
        CURRENT_OF("SCAN", "CURRENT_OF", CurrentOfNode.class),
        SUBQUERY("SUBQUERY", "SUBQUERY", FromSubquery.class),
        QUERY_BLOCK("QUERY", "QUERY_BLOCK", SelectNode.class),
        GENERIC("RESULT_SET", "GENERIC");

        private static final Operation[] NODE_TYPES = {
                INDEX_TO_BASE_ROW, OUTER_JOIN, JOIN, PROJECT_RESTRICT, GROUP_BY,
                DISTINCT, ORDER_BY, ROW_COUNT, UNION, INTERSECT_OR_EXCEPT,
                ROW_VALUES, WINDOW, HASH_TABLE, MATERIALIZE, MATERIALIZED_SUBQUERY,
                NORMALIZE, SCROLL_INSENSITIVE, VTI_SCAN, CURRENT_OF, SUBQUERY, QUERY_BLOCK
        };

        private final String logical;
        private final String physical;
        private final Class<? extends ResultSetNode> nodeType;

        Operation(String logical, String physical) {
            this(logical, physical, null);
        }

        Operation(String logical, String physical, Class<? extends ResultSetNode> nodeType) {
            this.logical = logical;
            this.physical = physical;
            this.nodeType = nodeType;
        }

        private static Operation forNode(ResultSetNode node) {
            for (Operation operation : NODE_TYPES) {
                if (operation.nodeType.isInstance(node)) return operation;
            }
            return GENERIC;
        }

        String logical() { return logical; }
        String physical() { return physical; }
    }
}
