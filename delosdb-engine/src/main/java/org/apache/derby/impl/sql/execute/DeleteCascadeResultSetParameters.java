/*

   Derby - Class org.apache.derby.impl.sql.execute.DeleteCascadeResultSetParameters

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

import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/** Named constructor payload for {@link DeleteCascadeResultSet}. */
final class DeleteCascadeResultSetParameters {
    final NoPutResultSet source;
    final Activation activation;
    final int constantActionItem;
    final ConstantAction constantAction;
    final ResultSet[] dependentResultSets;
    final String resultSetId;

    DeleteCascadeResultSetParameters(
            NoPutResultSet source,
            Activation activation,
            int constantActionItem,
            ResultSet[] dependentResultSets,
            String resultSetId) throws StandardException {
        this.source = source;
        this.activation = activation;
        this.constantActionItem = constantActionItem;
        this.constantAction = constantActionItem == -1
                ? activation.getConstantAction()
                : (ConstantAction) activation.getPreparedStatement().getSavedObject(constantActionItem);
        this.dependentResultSets = dependentResultSets;
        this.resultSetId = resultSetId;
    }

    boolean isRootTable() {
        return constantActionItem == -1;
    }
}
