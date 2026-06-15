/*

   Derby - Class org.apache.derby.impl.sql.execute.SubqueryResultSetParameters

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

import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/**
 * Constructor payload for subquery result sets.
 */
final class SubqueryResultSetParameters
{
    final NoPutResultSet source;
    final Activation activation;
    final GeneratedMethod emptyRow;
    final int cardinalityCheck;
    final int resultSetNumber;
    final int subqueryNumber;
    final int pointOfAttachment;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    SubqueryResultSetParameters(
            NoPutResultSet source,
            Activation activation,
            GeneratedMethod emptyRow,
            int cardinalityCheck,
            int resultSetNumber,
            int subqueryNumber,
            int pointOfAttachment,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source = source;
        this.activation = activation;
        this.emptyRow = emptyRow;
        this.cardinalityCheck = cardinalityCheck;
        this.resultSetNumber = resultSetNumber;
        this.subqueryNumber = subqueryNumber;
        this.pointOfAttachment = pointOfAttachment;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
