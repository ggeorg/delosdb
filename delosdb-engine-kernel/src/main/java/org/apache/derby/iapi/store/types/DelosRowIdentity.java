/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRowIdentity

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
package org.apache.derby.iapi.store.types;

/**
 * Opaque provider-native row identity.
 *
 * <p>An identity is meaningful only to the {@link DelosTableAccess}
 * implementation that produced it.  MVCC may wrap a provider key, Derby heap may
 * wrap a row-location object, and storeless normally has no row identity.  This
 * interface deliberately promises no cross-provider equality semantics.</p>
 */
public interface DelosRowIdentity {
    String providerName();

    Object nativeIdentity();
}
