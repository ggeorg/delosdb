/*

   Derby - Class org.apache.derby.impl.sql.compile.DelosVersionedStorageQueryTreeClassifier

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

import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.compile.CompilerContext;
import org.apache.derby.iapi.sql.compile.Visitable;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.conn.StatementContext;
import org.apache.derby.shared.common.error.StandardException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimal JavaCC/QueryTreeNode classifier for the temporary delos_mvcc SQL bridge.
 *
 * <p>This is deliberately not a binder, optimizer, or executor integration.  It
 * uses Derby's real JavaCC parser through the current compiler context, reads
 * the resulting query-tree node shape, and returns a narrow planned route for
 * one already-supported bridge statement. Regex routing remains the fallback
 * until each regex route has an equivalent QueryTreeNode route.</p>
 */
public final class DelosVersionedStorageQueryTreeClassifier {
    private DelosVersionedStorageQueryTreeClassifier() {
    }

    public static Optional<SelectWhereEqualsRoute> selectWhereEquals(String sql) {
        return selectWhereComparison(sql)
                .filter(route -> "=".equals(route.operator()))
                .map(route -> new SelectWhereEqualsRoute(
                        route.tableName(),
                        route.columnName(),
                        route.rawValue()));
    }

    public static Optional<SelectWhereComparisonRoute> selectWhereComparison(String sql) {
        return parseStatement(sql).flatMap(parsed -> classifySelectWhereComparison(sql, parsed));
    }

    public static Optional<InsertValuesRoute> insertValues(String sql) {
        return parseStatement(sql).flatMap(parsed -> classifyInsertValues(sql, parsed));
    }

    public static Optional<DeleteWhereEqualsRoute> deleteWhereEquals(String sql) {
        return parseStatement(sql).flatMap(parsed -> classifyDeleteWhereEquals(sql, parsed));
    }

    private static Optional<Visitable> parseStatement(String sql) {
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContextOrNull(
                LanguageConnectionContext.CONTEXT_ID);
        if (lcc == null) {
            return Optional.empty();
        }

        StatementContext statementContext = null;
        CompilerContext compilerContext = null;
        try {
            if (lcc.getStatementDepth() == 0 || lcc.getStatementContext() == null) {
                statementContext = lcc.pushStatementContext(true, true, sql, null, false, 0L);
            }
            compilerContext = lcc.pushCompilerContext();
            return Optional.of(compilerContext.getParser().parseStatement(sql));
        } catch (StandardException e) {
            return Optional.empty();
        } finally {
            if (compilerContext != null) {
                lcc.popCompilerContext(compilerContext);
            }
            if (statementContext != null) {
                lcc.popStatementContext(statementContext, null);
            }
        }
    }

    static Optional<SelectWhereComparisonRoute> classifySelectWhereComparison(String sql, Visitable parsed) {
        if (!(parsed instanceof CursorNode cursor)) {
            return Optional.empty();
        }
        if (cursorHasUnsupportedClauses(cursor)) {
            return Optional.empty();
        }
        if (!(cursor.getResultSetNode() instanceof SelectNode select)) {
            return Optional.empty();
        }
        if (select.groupByList != null || select.havingClause != null || select.windows != null) {
            return Optional.empty();
        }
        if (!isSelectStar(select.getResultColumns())) {
            return Optional.empty();
        }
        if (select.fromList == null || select.fromList.size() != 1) {
            return Optional.empty();
        }
        if (!(select.fromList.elementAt(0) instanceof FromBaseTable fromBaseTable)) {
            return Optional.empty();
        }
        if (!(select.whereClause instanceof BinaryRelationalOperatorNode comparison)) {
            return Optional.empty();
        }

        ColumnLiteralComparison pair = columnLiteralComparison(comparison).orElse(null);
        if (pair == null) {
            return Optional.empty();
        }
        if (!columnQualifiesSingleFromTable(pair.column(), fromBaseTable.tableName)) {
            return Optional.empty();
        }

        String literalSql = sqlSlice(sql, pair.literal())
                .or(() -> constantSqlLiteral(pair.literal()))
                .orElse(null);
        if (literalSql == null || literalSql.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new SelectWhereComparisonRoute(
                fromBaseTable.tableName.getFullTableName(),
                pair.column().getColumnName(),
                pair.operator(),
                literalSql));
    }

    static Optional<InsertValuesRoute> classifyInsertValues(String sql, Visitable parsed) {
        if (!(parsed instanceof InsertNode insert)) {
            return Optional.empty();
        }
        if (insert.targetTableName == null) {
            return Optional.empty();
        }
        if (privateFieldValue(insert, "targetColumnList") != null) {
            return Optional.empty();
        }
        ResultSetNode source = insert.getResultSetNode();
        if (!(source instanceof RowResultSetNode row)) {
            return Optional.empty();
        }
        ResultColumnList resultColumns = row.getResultColumns();
        if (resultColumns == null || resultColumns.size() == 0) {
            return Optional.empty();
        }

        List<String> values = new ArrayList<>(resultColumns.size());
        for (int index = 0; index < resultColumns.size(); index++) {
            ResultColumn resultColumn = resultColumns.elementAt(index);
            if (!(resultColumn.getExpression() instanceof ConstantNode literal)) {
                return Optional.empty();
            }
            String literalSql = sqlSlice(sql, literal)
                    .or(() -> constantSqlLiteral(literal))
                    .orElse(null);
            if (literalSql == null || literalSql.isBlank()) {
                return Optional.empty();
            }
            values.add(literalSql);
        }

        return Optional.of(new InsertValuesRoute(
                insert.targetTableName.getFullTableName(),
                String.join(", ", values)));
    }

    static Optional<DeleteWhereEqualsRoute> classifyDeleteWhereEquals(String sql, Visitable parsed) {
        if (!(parsed instanceof DeleteNode delete)) {
            return Optional.empty();
        }
        if (delete.targetTableName == null) {
            return Optional.empty();
        }
        if (!(delete.getResultSetNode() instanceof SelectNode select)) {
            return Optional.empty();
        }
        if (select.groupByList != null || select.havingClause != null || select.windows != null) {
            return Optional.empty();
        }
        if (select.fromList == null || select.fromList.size() != 1) {
            return Optional.empty();
        }
        if (!(select.fromList.elementAt(0) instanceof FromBaseTable fromBaseTable)) {
            return Optional.empty();
        }
        if (!delete.targetTableName.getFullTableName().equalsIgnoreCase(fromBaseTable.tableName.getFullTableName())) {
            return Optional.empty();
        }
        if (!(select.whereClause instanceof BinaryRelationalOperatorNode comparison)) {
            return Optional.empty();
        }

        ColumnLiteralComparison pair = columnLiteralComparison(comparison).orElse(null);
        if (pair == null || !"=".equals(pair.operator())) {
            return Optional.empty();
        }
        if (!columnQualifiesSingleFromTable(pair.column(), fromBaseTable.tableName)) {
            return Optional.empty();
        }

        String literalSql = sqlSlice(sql, pair.literal())
                .or(() -> constantSqlLiteral(pair.literal()))
                .orElse(null);
        if (literalSql == null || literalSql.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new DeleteWhereEqualsRoute(
                delete.targetTableName.getFullTableName(),
                pair.column().getColumnName(),
                literalSql));
    }

    private static boolean cursorHasUnsupportedClauses(CursorNode cursor) {
        return privateFieldValue(cursor, "orderByList") != null
                || privateFieldValue(cursor, "offset") != null
                || privateFieldValue(cursor, "fetchFirst") != null;
    }

    private static Object privateFieldValue(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            return new Object();
        }
    }

    private static boolean isSelectStar(ResultColumnList resultColumns) {
        return resultColumns != null
                && resultColumns.size() == 1
                && resultColumns.elementAt(0) instanceof AllResultColumn;
    }

    private static Optional<ColumnLiteralComparison> columnLiteralComparison(BinaryRelationalOperatorNode comparison) {
        Optional<String> operator = comparisonOperator(comparison.kind);
        if (operator.isEmpty()) {
            return Optional.empty();
        }
        if (comparison.leftOperand instanceof ColumnReference column
                && comparison.rightOperand instanceof ConstantNode literal) {
            return Optional.of(new ColumnLiteralComparison(column, literal, operator.get()));
        }
        if (comparison.rightOperand instanceof ColumnReference column
                && comparison.leftOperand instanceof ConstantNode literal) {
            return invertComparisonOperator(operator.get())
                    .map(invertedOperator -> new ColumnLiteralComparison(column, literal, invertedOperator));
        }
        return Optional.empty();
    }

    private static Optional<String> comparisonOperator(int kind) {
        return switch (kind) {
            case BinaryRelationalOperatorNode.K_EQUALS -> Optional.of("=");
            case BinaryRelationalOperatorNode.K_GREATER_EQUALS -> Optional.of(">=");
            case BinaryRelationalOperatorNode.K_GREATER_THAN -> Optional.of(">");
            case BinaryRelationalOperatorNode.K_LESS_EQUALS -> Optional.of("<=");
            case BinaryRelationalOperatorNode.K_LESS_THAN -> Optional.of("<");
            default -> Optional.empty();
        };
    }

    private static Optional<String> invertComparisonOperator(String operator) {
        return switch (operator) {
            case "=" -> Optional.of("=");
            case ">=" -> Optional.of("<=");
            case ">" -> Optional.of("<");
            case "<=" -> Optional.of(">=");
            case "<" -> Optional.of(">");
            default -> Optional.empty();
        };
    }

    private static boolean columnQualifiesSingleFromTable(ColumnReference column, TableName tableName) {
        TableName qualifier = column.getQualifiedTableName();
        if (qualifier == null) {
            return true;
        }
        if (!qualifier.getTableName().equalsIgnoreCase(tableName.getTableName())) {
            return false;
        }
        String qualifierSchema = qualifier.getSchemaName();
        String tableSchema = tableName.getSchemaName();
        return qualifierSchema == null || tableSchema == null || qualifierSchema.equalsIgnoreCase(tableSchema);
    }


    private static Optional<String> constantSqlLiteral(ConstantNode literal) {
        try {
            DataValueDescriptor value = literal.getValue();
            if (value == null || value.isNull()) {
                return Optional.of("NULL");
            }
            String text = value.getString();
            if (literal instanceof CharConstantNode) {
                return Optional.of("'" + text.replace("'", "''") + "'");
            }
            return Optional.of(text);
        } catch (StandardException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> sqlSlice(String sql, QueryTreeNode node) {
        int begin = node.getBeginOffset();
        int end = node.getEndOffset();
        if (begin < 0 || end < begin || end >= sql.length()) {
            return Optional.empty();
        }
        return Optional.of(sql.substring(begin, end + 1).trim());
    }

    private record ColumnLiteralComparison(ColumnReference column, ConstantNode literal, String operator) {
    }

    public record InsertValuesRoute(String tableName, String values) {
        public InsertValuesRoute {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("tableName must not be blank");
            }
            if (values == null || values.isBlank()) {
                throw new IllegalArgumentException("values must not be blank");
            }
        }
    }

    public record DeleteWhereEqualsRoute(String tableName, String columnName, String rawValue) {
        public DeleteWhereEqualsRoute {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("tableName must not be blank");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("rawValue must not be blank");
            }
        }
    }

    public record SelectWhereComparisonRoute(String tableName, String columnName, String operator, String rawValue) {
        public SelectWhereComparisonRoute {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("tableName must not be blank");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            if (operator == null || operator.isBlank()) {
                throw new IllegalArgumentException("operator must not be blank");
            }
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("rawValue must not be blank");
            }
        }
    }

    public record SelectWhereEqualsRoute(String tableName, String columnName, String rawValue) {
        public SelectWhereEqualsRoute {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("tableName must not be blank");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("rawValue must not be blank");
            }
        }
    }
}
