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

/** Compile-only wrapper for {@code EXPLAIN <statement>}. */
final class ExplainNode extends CursorNode {
    private final StatementNode explained;

    ExplainNode(StatementNode explained, ContextManager cm) throws StandardException {
        super("EXPLAIN", new ExplainResultSetNode(cm), null, null, null, null, false,
                READ_ONLY, null, false, cm);
        this.explained = explained;
    }

    @Override
    public void bindStatement() throws StandardException {
        explained.bindStatement();
        super.bindStatement();
    }

    @Override
    public void optimizeStatement() throws StandardException {
        explained.optimizeStatement();
        super.optimizeStatement();
    }

    @Override
    public boolean referencesSessionSchema() throws StandardException {
        return explained.referencesSessionSchema();
    }

    @Override
    public StablePlanModel buildStablePlanModel(String sourceText, String compilationSchema) {
        return explained.buildStablePlanModel(explainedSource(sourceText), compilationSchema);
    }

    private static String explainedSource(String source) {
        if (source == null) return "";
        int start = 0;
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
        if (source.regionMatches(true, start, "EXPLAIN", 0, 7)) {
            start += 7;
            while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
        }
        return source.substring(start);
    }
}
