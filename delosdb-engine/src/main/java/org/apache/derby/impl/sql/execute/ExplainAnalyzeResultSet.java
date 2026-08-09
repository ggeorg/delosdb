/*

   Derby - Class org.apache.derby.impl.sql.execute.ExplainAnalyzeResultSet

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

package org.apache.derby.impl.sql.execute;

import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.sql.execute.ResultSetStatistics;
import org.apache.derby.iapi.sql.execute.StablePlanExecutionEvidence;
import org.apache.derby.iapi.sql.compile.StablePlanExecutionRenderer;
import org.apache.derby.iapi.types.DataValueFactory;
import org.apache.derby.impl.sql.execute.rts.StablePlanExecutionEvidenceBuilder;
import org.apache.derby.shared.common.error.StandardException;

/** Executes a query once and returns one row containing its stable plan plus runtime evidence. */
final class ExplainAnalyzeResultSet extends RowResultSet {
    private final NoPutResultSet source;
    private ExecRow resultRow;
    private boolean returned;

    ExplainAnalyzeResultSet(
            Activation activation,
            NoPutResultSet source,
            int resultSetNumber,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost) {
        super(activation, (ExecRow) null, true, resultSetNumber,
                optimizerEstimatedRowCount, optimizerEstimatedCost);
        this.source = source;
    }

    @Override
    public void openCore() throws StandardException {
        long rootRowsReturned = 0;
        ResultSetStatistics statistics;
        source.openCore();
        try {
            while (source.getNextRowCore() != null) {
                rootRowsReturned++;
            }
            statistics = activation.getExecutionFactory()
                    .getResultSetStatisticsFactory().getResultSetStatistics(source);
        } finally {
            source.close();
        }

        StablePlanModel plan = activation.getPreparedStatement().getStablePlanModel();
        StablePlanExecutionEvidence evidence = StablePlanExecutionEvidenceBuilder.build(
                plan,
                activation.getPreparedStatement().getStablePlanResultSetNumbers(),
                statistics,
                rootRowsReturned);
        DataValueFactory dvf = activation.getLanguageConnectionContext().getDataValueFactory();
        resultRow = activation.getExecutionFactory().getValueRow(2);
        resultRow.setColumn(1, dvf.getClobDataValue(
                StablePlanExecutionRenderer.text(plan, evidence), null));
        resultRow.setColumn(2, dvf.getClobDataValue(
                StablePlanExecutionRenderer.json(plan, evidence), null));
        returned = false;
        super.openCore();
    }

    @Override
    public ExecRow getNextRowCore() throws StandardException {
        currentRow = isOpen && !returned ? resultRow : null;
        if (currentRow != null) {
            returned = true;
            rowsReturned++;
        }
        setCurrentRow(currentRow);
        return currentRow;
    }

    @Override
    public void close() throws StandardException {
        if (isOpen) {
            resultRow = null;
            returned = false;
        }
        super.close();
    }
}
