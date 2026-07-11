/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccScanInfo

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

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.shared.common.error.StandardException;

/** Immutable performance snapshot for an MVCC scan. */
final class MvccScanInfo implements ScanInfo {
    private static final String SCAN_TYPE = "scanType";
    private static final String ROWS_VISITED = "numRowsVisited";
    private static final String ROWS_QUALIFIED = "numRowsQualified";
    private static final String COLUMNS_FETCHED = "numColumnsFetched";
    private static final String COLUMNS_FETCHED_BIT_SET = "columnsFetchedBitSet";

    private final long rowsVisited;
    private final long rowsQualified;
    private final FormatableBitSet columnsFetched;

    MvccScanInfo(long rowsVisited, long rowsQualified, FormatableBitSet columnsFetched) {
        this.rowsVisited = rowsVisited;
        this.rowsQualified = rowsQualified;
        this.columnsFetched = columnsFetched == null
                ? null
                : (FormatableBitSet) columnsFetched.clone();
    }

    @Override
    public Properties getAllScanInfo(Properties properties) throws StandardException {
        Properties result = properties == null ? new Properties() : properties;
        result.setProperty(SCAN_TYPE, "delos_mvcc");
        result.setProperty(ROWS_VISITED, Long.toString(rowsVisited));
        result.setProperty(ROWS_QUALIFIED, Long.toString(rowsQualified));
        if (columnsFetched == null) {
            result.setProperty(COLUMNS_FETCHED_BIT_SET, "all");
        } else {
            result.setProperty(COLUMNS_FETCHED, Integer.toString(countSetBits(columnsFetched)));
            result.setProperty(COLUMNS_FETCHED_BIT_SET, columnsFetched.toString());
        }
        return result;
    }

    private static int countSetBits(FormatableBitSet bitSet) {
        int count = 0;
        for (int index = 0; index < bitSet.size(); index++) {
            if (bitSet.get(index)) {
                count++;
            }
        }
        return count;
    }
}
