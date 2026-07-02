/*

   Derby - Class org.apache.derby.iapi.store.types.StoreOrderable

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
 * SQL-neutral ordering constants and marker for store-facing values.
 *
 * <p>The numeric values intentionally match Derby's inherited
 * {@code org.apache.derby.iapi.types.Orderable} constants because storage
 * qualifiers cross the DelosDB storage boundary using those existing values.</p>
 */
public interface StoreOrderable
{
    int ORDER_OP_LESSTHAN = 1;
    int ORDER_OP_EQUALS = 2;
    int ORDER_OP_LESSOREQUALS = 3;
    int ORDER_OP_GREATERTHAN = 4;
    int ORDER_OP_GREATEROREQUALS = 5;
}
