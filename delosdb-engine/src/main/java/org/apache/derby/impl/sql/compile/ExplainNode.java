/*

   Derby - Class org.apache.derby.impl.sql.compile.ExplainNode

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

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Wrapper for compile-only EXPLAIN and query-only EXPLAIN ANALYZE. */
final class ExplainNode extends CursorNode {
    private final StatementNode explained;
    private final boolean analyze;

    ExplainNode(StatementNode explained, boolean analyze, ContextManager cm)
            throws StandardException {
        super(analyze ? "EXPLAIN ANALYZE" : "EXPLAIN",
                new ExplainResultSetNode(analyze, cm), null, null, null, null, false,
                READ_ONLY, null, false, cm);
        if (analyze && !(explained instanceof CursorNode)) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED, "EXPLAIN ANALYZE for non-query statements");
        }
        this.explained = explained;
        this.analyze = analyze;
    }

    @Override
    public void bindStatement() throws StandardException {
        explained.bindStatement();
        if (analyze && ((CursorNode) explained).getUpdateMode() != READ_ONLY) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED, "EXPLAIN ANALYZE for updatable queries");
        }
        super.bindStatement();
    }

    @Override
    public void optimizeStatement() throws StandardException {
        explained.optimizeStatement();
        if (analyze) {
            ((ExplainResultSetNode) resultSet).setAnalyzedSource(
                    ((CursorNode) explained).resultSet);
        }
        super.optimizeStatement();
    }

    @Override
    public boolean referencesSessionSchema() throws StandardException {
        return explained.referencesSessionSchema();
    }

    @Override
    public StablePlanModel buildStablePlanModel(String sourceText, String compilationSchema) {
        return explained.buildStablePlanModel(
                explainedSource(sourceText, analyze), compilationSchema);
    }

    @Override
    public int[] buildStablePlanResultSetNumbers() {
        return analyze ? StablePlanModelBuilder.resultSetNumbers(explained) : null;
    }

    private static String explainedSource(String source, boolean analyze) {
        if (source == null) return "";
        int start = skipKeyword(source, 0, "EXPLAIN");
        if (analyze) start = skipKeyword(source, start, "ANALYZE");
        return source.substring(start);
    }

    private static int skipKeyword(String source, int start, String keyword) {
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
        if (source.regionMatches(true, start, keyword, 0, keyword.length())) {
            start += keyword.length();
            while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
        }
        return start;
    }
}
