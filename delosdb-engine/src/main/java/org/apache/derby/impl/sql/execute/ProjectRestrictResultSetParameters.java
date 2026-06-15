/*

   Derby - Class org.apache.derby.impl.sql.execute.ProjectRestrictResultSetParameters

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

import org.apache.derby.catalog.UUID;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/**
 * Constructor payload for project/restrict result sets.
 *
 * <p>The generated result-set factory passes several generated methods, saved
 * object indexes, and booleans into this result set. Keeping those fields named
 * removes a fragile positional constructor path without changing projection,
 * restriction, or check-constraint validation semantics.</p>
 */
final class ProjectRestrictResultSetParameters
{
    final NoPutResultSet source;
    final Activation activation;
    final GeneratedMethod restriction;
    final GeneratedMethod projection;
    final int resultSetNumber;
    final GeneratedMethod constantRestriction;
    final int mapRefItem;
    final int cloneMapItem;
    final boolean reuseResult;
    final boolean doesProjection;
    final boolean validatingCheckConstraint;
    final UUID validatingBaseTableUUID;
    final double optimizerEstimatedRowCount;
    final double optimizerEstimatedCost;

    ProjectRestrictResultSetParameters(
            NoPutResultSet source,
            Activation activation,
            GeneratedMethod restriction,
            GeneratedMethod projection,
            int resultSetNumber,
            GeneratedMethod constantRestriction,
            int mapRefItem,
            int cloneMapItem,
            boolean reuseResult,
            boolean doesProjection,
            boolean validatingCheckConstraint,
            UUID validatingBaseTableUUID,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost)
    {
        this.source = source;
        this.activation = activation;
        this.restriction = restriction;
        this.projection = projection;
        this.resultSetNumber = resultSetNumber;
        this.constantRestriction = constantRestriction;
        this.mapRefItem = mapRefItem;
        this.cloneMapItem = cloneMapItem;
        this.reuseResult = reuseResult;
        this.doesProjection = doesProjection;
        this.validatingCheckConstraint = validatingCheckConstraint;
        this.validatingBaseTableUUID = validatingBaseTableUUID;
        this.optimizerEstimatedRowCount = optimizerEstimatedRowCount;
        this.optimizerEstimatedCost = optimizerEstimatedCost;
    }
}
