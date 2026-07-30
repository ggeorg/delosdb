/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import org.apache.derby.iapi.store.types.DelosDatabaseMemorySnapshot;
import org.apache.derby.iapi.store.types.DelosStorageText;

/** Contract proof for consolidated storage text validation. */
public final class StorageTextValidationTest extends MvccSqlTestSupport {

    public void testSharedValidationPreservesNormalizationAndMessages() {
        assertEquals("value", DelosStorageText.requireNonBlank(
                "  value  ", "field"));

        try {
            DelosStorageText.requireNonBlank(null, "field");
            fail("null value should fail");
        } catch (NullPointerException expected) {
            assertEquals("field", expected.getMessage());
        }

        try {
            DelosStorageText.requireNonBlank("  ", "field");
            fail("blank value should fail");
        } catch (IllegalArgumentException expected) {
            assertEquals("field must not be blank", expected.getMessage());
        }
    }

    public void testStorageContractsUseTrimmedValues() {
        DelosDatabaseMemorySnapshot snapshot =
                new DelosDatabaseMemorySnapshot(
                        DelosDatabaseMemorySnapshot.CURRENT_SCHEMA_VERSION,
                        "derby",
                        "  memory:shared-validation  ",
                        true,
                        true,
                        128L,
                        64L,
                        64L,
                        0L,
                        1);

        assertEquals("memory:shared-validation", snapshot.databaseIdentity());
    }
}
