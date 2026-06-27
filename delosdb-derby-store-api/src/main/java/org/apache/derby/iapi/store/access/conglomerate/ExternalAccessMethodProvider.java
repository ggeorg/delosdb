/*

   Derby - Interface org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider

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

package org.apache.derby.iapi.store.access.conglomerate;

import java.util.Properties;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Provider hook for access-method factories that are not owned by the
 * inherited Derby storage implementation module.
 *
 * <p>This keeps {@code delosdb-storage-derby} independent of DelosDB-owned
 * storage providers while still letting {@code RAMAccessManager} discover and
 * register additional conglomerate factories through Derby's inherited
 * store/access contracts.</p>
 */
public interface ExternalAccessMethodProvider {
    boolean supportsImplementation(String implementationId);

    boolean supportsFactoryId(int factoryId);

    MethodFactory bootForImplementation(
            boolean create,
            Properties serviceProperties,
            String implementationId) throws StandardException;

    ConglomerateFactory bootForFactoryId(
            boolean create,
            Properties serviceProperties,
            int factoryId) throws StandardException;
}
