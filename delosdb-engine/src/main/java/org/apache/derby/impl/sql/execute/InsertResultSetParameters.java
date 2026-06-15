/*

   Derby - Class org.apache.derby.impl.sql.execute.InsertResultSetParameters

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
 * Named constructor payload for {@link InsertResultSet}.
 *
 * <p>The generated result-set factory historically passed these values as a
 * long positional argument list.  Keeping them together makes the DML write
 * path easier to review without changing execution semantics.</p>
 */
final class InsertResultSetParameters {
    final NoPutResultSet source;
    final GeneratedMethod generationClauses;
    final GeneratedMethod checkGM;
    final int fullTemplate;
    final String schemaName;
    final String tableName;
    final Activation activation;

    InsertResultSetParameters(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            int fullTemplate,
            String schemaName,
            String tableName,
            Activation activation) {
        this.source = source;
        this.generationClauses = generationClauses;
        this.checkGM = checkGM;
        this.fullTemplate = fullTemplate;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.activation = activation;
    }
}
