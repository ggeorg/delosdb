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
            Visitable parsed = compilerContext.getParser().parseStatement(sql);
            return classifySelectWhereEquals(sql, parsed);
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

    static Optional<SelectWhereEqualsRoute> classifySelectWhereEquals(String sql, Visitable parsed) {
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
        if (comparison.kind != BinaryRelationalOperatorNode.K_EQUALS || !"=".equals(comparison.operator)) {
            return Optional.empty();
        }

        ColumnLiteralPair pair = columnLiteralPair(comparison.leftOperand, comparison.rightOperand)
                .or(() -> columnLiteralPair(comparison.rightOperand, comparison.leftOperand))
                .orElse(null);
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

        return Optional.of(new SelectWhereEqualsRoute(
                fromBaseTable.tableName.getFullTableName(),
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

    private static Optional<ColumnLiteralPair> columnLiteralPair(ValueNode possibleColumn, ValueNode possibleLiteral) {
        if (possibleColumn instanceof ColumnReference column && possibleLiteral instanceof ConstantNode literal) {
            return Optional.of(new ColumnLiteralPair(column, literal));
        }
        return Optional.empty();
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

    private record ColumnLiteralPair(ColumnReference column, ConstantNode literal) {
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
