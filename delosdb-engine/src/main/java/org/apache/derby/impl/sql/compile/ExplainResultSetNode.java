/*

   Derby - Class org.apache.derby.impl.sql.compile.ExplainResultSetNode

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

import java.sql.Types;

import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.types.TypeId;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.ClassName;

/** One-row result-set node used by SQL EXPLAIN. */
final class ExplainResultSetNode extends RowResultSetNode {
    ExplainResultSetNode(ContextManager cm) throws StandardException {
        super(columns(cm), null, cm);
    }

    @Override
    void generate(ActivationClassBuilder acb, MethodBuilder mb) throws StandardException {
        setCostEstimate(getFinalCostEstimate());
        assignResultSetNumber();
        acb.pushGetResultSetFactoryExpression(mb);
        acb.pushThisAsActivation(mb);
        mb.push(getResultSetNumber());
        mb.push(getCostEstimate().rowCount());
        mb.push(getCostEstimate().getEstimatedCost());
        mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "getExplainResultSet",
                ClassName.NoPutResultSet, 4);
    }

    private static ResultColumnList columns(ContextManager cm) throws StandardException {
        ResultColumnList columns = new ResultColumnList(cm);
        columns.addResultColumn(new ResultColumn("PLAN_TEXT", clob(cm), cm));
        columns.addResultColumn(new ResultColumn("PLAN_JSON", clob(cm), cm));
        return columns;
    }

    private static CharConstantNode clob(ContextManager cm) throws StandardException {
        CharConstantNode value = new CharConstantNode("", cm);
        value.setType(TypeId.getBuiltInTypeId(Types.CLOB), true, Integer.MAX_VALUE);
        return value;
    }
}
