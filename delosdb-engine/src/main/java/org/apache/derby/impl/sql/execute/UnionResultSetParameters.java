/*

   Derby - Class org.apache.derby.impl.sql.execute.UnionResultSetParameters

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
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/**
 * Constructor payload for UNION ALL result sets.
 */
final class UnionResultSetParameters
{
    final NoPutResultSet source1;
    final NoPutResultSet source2;
    final Activation activation;
    final int resultSetNumber;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    UnionResultSetParameters(
            NoPutResultSet source1,
            NoPutResultSet source2,
            Activation activation,
            int resultSetNumber,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source1 = source1;
        this.source2 = source2;
        this.activation = activation;
        this.resultSetNumber = resultSetNumber;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
