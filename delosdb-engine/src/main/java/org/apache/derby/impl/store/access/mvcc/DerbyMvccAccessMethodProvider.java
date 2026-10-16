/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.DerbyMvccAccessMethodProvider

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

package org.apache.derby.impl.store.access.mvcc;

import java.util.Properties;

import org.apache.derby.iapi.store.access.AccessFactoryGlobals;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodBootContext;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.ExternalAccessMethodProvider;
import org.apache.derby.iapi.store.access.conglomerate.MethodFactory;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Service-loaded Derby access-method provider for the DelosDB MVCC adapter.
 *
 * <p>The inherited Derby storage implementation discovers this class through
 * the neutral {@link ExternalAccessMethodProvider} service hook, so
 * {@code delosdb-storage-derby} does not compile against or own MVCC provider
 * classes.</p>
 */
public final class DerbyMvccAccessMethodProvider implements ExternalAccessMethodProvider {
    @Override
    public boolean supportsImplementation(String implementationId) {
        return MvccConglomerateFactory.IMPLEMENTATION_ID.equals(implementationId);
    }

    @Override
    public boolean supportsFactoryId(int factoryId) {
        return factoryId == ConglomerateFactory.MVCC_FACTORY_ID;
    }

    @Override
    public MethodFactory bootForImplementation(
            AccessMethodBootContext context,
            String implementationId) throws StandardException {
        if (!supportsImplementation(implementationId)) {
            return null;
        }

        Properties conglomProperties = context.serviceProperties();
        conglomProperties.put(AccessFactoryGlobals.CONGLOM_PROP, implementationId);
        MvccConglomerateFactory factory = new MvccConglomerateFactory();
        if (!factory.canSupport(conglomProperties)) {
            return null;
        }
        factory.boot(context.withServiceProperties(conglomProperties));
        return factory;
    }

    @Override
    public ConglomerateFactory bootForFactoryId(
            AccessMethodBootContext context,
            int factoryId) throws StandardException {
        if (!supportsFactoryId(factoryId)) {
            return null;
        }

        MethodFactory factory = bootForImplementation(
                context,
                MvccConglomerateFactory.IMPLEMENTATION_ID);
        return factory instanceof ConglomerateFactory conglomerateFactory
                ? conglomerateFactory
                : null;
    }
}
