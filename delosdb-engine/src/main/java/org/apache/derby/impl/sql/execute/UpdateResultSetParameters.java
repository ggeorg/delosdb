/*

   Derby - Class org.apache.derby.impl.sql.execute.UpdateResultSetParameters

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
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.ResultDescription;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;

/** Named constructor payload for {@link UpdateResultSet}. */
final class UpdateResultSetParameters {
    final NoPutResultSet source;
    final GeneratedMethod generationClauses;
    final GeneratedMethod checkGM;
    final Activation activation;
    final ConstantAction constantAction;
    final ResultDescription resultDescription;
    final boolean forceDeferred;

    private UpdateResultSetParameters(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation,
            ConstantAction constantAction,
            ResultDescription resultDescription,
            boolean forceDeferred) {
        this.source = source;
        this.generationClauses = generationClauses;
        this.checkGM = checkGM;
        this.activation = activation;
        this.constantAction = constantAction;
        this.resultDescription = resultDescription;
        this.forceDeferred = forceDeferred;
    }

    static UpdateResultSetParameters normal(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation) {
        return new UpdateResultSetParameters(
                source,
                generationClauses,
                checkGM,
                activation,
                activation.getConstantAction(),
                null,
                false);
    }

    static UpdateResultSetParameters cascade(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation,
            int constantActionItem,
            int rsdItem) throws StandardException {
        return new UpdateResultSetParameters(
                source,
                generationClauses,
                checkGM,
                activation,
                (ConstantAction) activation.getPreparedStatement().getSavedObject(constantActionItem),
                (ResultDescription) activation.getPreparedStatement().getSavedObject(rsdItem),
                true);
    }
}
