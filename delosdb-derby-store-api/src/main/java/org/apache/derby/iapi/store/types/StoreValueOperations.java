/*

   Derby - Class org.apache.derby.iapi.store.types.StoreValueOperations

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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.shared.common.error.StandardException;

/** Operations implemented directly by store-native metadata values. */
public interface StoreValueOperations extends StoreDataValue
{
    StoreDataValue cloneHolder();

    StoreDataValue cloneValue(boolean forceMaterialization)
        throws StandardException;

    StoreDataValue getNewNull() throws StandardException;

    StoreDataValue recycle();

    int getLength() throws StandardException;

    long getLong() throws StandardException;

    String getString() throws StandardException;

    boolean isNull();

    Object getObject() throws StandardException;

    InputStream getStream() throws StandardException;

    int estimateMemoryUsage();

    void setValue(StoreDataValue source) throws StandardException;

    void setIntValue(int value);

    void setLongValue(long value);

    void restoreToNull();

    void readExternal(ObjectInput input) throws IOException, ClassNotFoundException;

    void readExternalFromArray(ArrayInputStream input)
        throws IOException, ClassNotFoundException;

    void writeExternal(ObjectOutput output) throws IOException;

    int compare(StoreDataValue other) throws StandardException;

    int compare(StoreDataValue other, boolean nullsOrderedLow)
        throws StandardException;

    boolean compare(
        int op,
        StoreDataValue other,
        boolean orderedNulls,
        boolean unknownRV)
        throws StandardException;

    boolean compare(
        int op,
        StoreDataValue other,
        boolean orderedNulls,
        boolean nullsOrderedLow,
        boolean unknownRV)
        throws StandardException;
}
