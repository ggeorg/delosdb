/*

   Derby - Class org.apache.derby.iapi.store.types.StoreTypeSupport

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

import java.io.InputStream;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-owned operations for SQL values that cross the legacy store boundary.
 *
 * <p>The kernel must not know Derby's inherited {@code iapi.types} package.
 * This bridge gives relocated store code an opaque operation surface while the
 * engine keeps the concrete Derby SQL value implementation.</p>
 */
public interface StoreTypeSupport
{
    StoreDataValue newSQLInteger();

    StoreDataValue newSQLLongint(long value);

    StoreDataValue newUserType();

    StoreDataValue newUserType(Object value);

    StoreDataValue cloneHolder(Object value);

    StoreDataValue cloneValue(Object value, boolean forceMaterialization)
        throws StandardException;

    StoreDataValue getNewNull(Object value) throws StandardException;

    StoreLocatedRow newLocatedRow(Object columnValues, Object rowLocation);

    StoreLocatedRow newLocatedRow(Object columnsAndRowLocation);

    Object[] locatedRowColumnValues(Object locatedRow);

    Object locatedRowLocation(Object locatedRow);

    Object[] flattenLocatedRow(Object columnValues, Object rowLocation);

    int compare(Object left, Object right) throws StandardException;

    int compare(Object left, Object right, boolean nullsOrderedLow)
        throws StandardException;

    boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean unknownRV)
        throws StandardException;

    boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean nullsOrderedLow,
        boolean unknownRV)
        throws StandardException;

    int getLength(Object value) throws StandardException;

    long getLong(Object value) throws StandardException;

    boolean isNull(Object value) throws StandardException;

    Object getObject(Object value) throws StandardException;

    InputStream getStream(Object value) throws StandardException;

    int estimateMemoryUsage(Object value);

    void setValue(Object target, Object source) throws StandardException;

    void setLongValue(Object target, long value);
}
