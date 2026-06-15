/*

   Derby - Class org.apache.derby.impl.sql.execute.LastIndexKeyResultSetParameters

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

/** Package-private constructor payload for LastIndexKeyResultSet. */
final class LastIndexKeyResultSetParameters
{
    final Activation activation;
    final int resultSetNumber;
    final int resultRowTemplate;
    final long conglomId;
    final String tableName;
    final String userSuppliedOptimizerOverrides;
    final String indexName;
    final int colRefItem;
    final int lockMode;
    final boolean tableLocked;
    final int isolationLevel;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    LastIndexKeyResultSetParameters(
            Activation activation,
            int resultSetNumber,
            int resultRowTemplate,
            long conglomId,
            String tableName,
            String userSuppliedOptimizerOverrides,
            String indexName,
            int colRefItem,
            int lockMode,
            boolean tableLocked,
            int isolationLevel,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.activation = activation;
        this.resultSetNumber = resultSetNumber;
        this.resultRowTemplate = resultRowTemplate;
        this.conglomId = conglomId;
        this.tableName = tableName;
        this.userSuppliedOptimizerOverrides = userSuppliedOptimizerOverrides;
        this.indexName = indexName;
        this.colRefItem = colRefItem;
        this.lockMode = lockMode;
        this.tableLocked = tableLocked;
        this.isolationLevel = isolationLevel;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
