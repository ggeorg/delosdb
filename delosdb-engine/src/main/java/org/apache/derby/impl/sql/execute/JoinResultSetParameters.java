/*

   Derby - Class org.apache.derby.impl.sql.execute.JoinResultSetParameters

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
 * Named constructor payload for join result sets.
 *
 * <p>The generated result-set factory has historically threaded many adjacent
 * primitive values through nested-loop and hash join constructors.  Keeping the
 * values in a package-private payload makes the factory path easier to audit
 * without changing the join execution algorithm.</p>
 */
final class JoinResultSetParameters
{
    final NoPutResultSet leftResultSet;
    final int leftNumCols;
    final NoPutResultSet rightResultSet;
    final int rightNumCols;
    final Activation activation;
    final GeneratedMethod restriction;
    final int resultSetNumber;
    final boolean oneRowRightSide;
    final boolean notExistsRightSide;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;
    final String userSuppliedOptimizerOverrides;

    JoinResultSetParameters(
            NoPutResultSet leftResultSet,
            int leftNumCols,
            NoPutResultSet rightResultSet,
            int rightNumCols,
            Activation activation,
            GeneratedMethod restriction,
            int resultSetNumber,
            boolean oneRowRightSide,
            boolean notExistsRightSide,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost,
            String userSuppliedOptimizerOverrides)
    {
        this.leftResultSet = leftResultSet;
        this.leftNumCols = leftNumCols;
        this.rightResultSet = rightResultSet;
        this.rightNumCols = rightNumCols;
        this.activation = activation;
        this.restriction = restriction;
        this.resultSetNumber = resultSetNumber;
        this.oneRowRightSide = oneRowRightSide;
        this.notExistsRightSide = notExistsRightSide;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
        this.userSuppliedOptimizerOverrides = userSuppliedOptimizerOverrides;
    }
}
