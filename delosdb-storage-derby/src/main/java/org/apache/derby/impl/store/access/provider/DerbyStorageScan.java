/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageScan

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
package org.apache.derby.impl.store.access.provider;

import java.util.List;
import java.util.Objects;

import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/** Scan adapter over an inherited Derby {@link ScanController}. */
public final class DerbyStorageScan implements DelosScan {
    private final ScanController scanController;
    private final DelosTableShape rowShape;
    private final StoreDataValue[] rowTemplate;
    private final DelosProjection projection;
    private DelosRow current;

    DerbyStorageScan(
            ScanController scanController,
            DelosTableShape rowShape,
            StoreDataValue[] rowTemplate,
            DelosProjection projection) {
        this.scanController = Objects.requireNonNull(scanController, "scanController");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
        this.rowTemplate = rowTemplate == null ? new StoreDataValue[0] : rowTemplate.clone();
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public boolean next() {
        try {
            if (!scanController.next()) {
                current = null;
                return false;
            }
            StoreDataValue[] fetched = DerbyStorageRows.fetchTemplate(rowTemplate);
            StoreRowLocation location = scanController.newRowLocationTemplate();
            scanController.fetch(fetched);
            scanController.fetchLocation(location);
            List<StoreDataValue> values = DerbyStorageRows.projectedValues(rowShape, fetched, projection);
            current = DelosRow.withIdentity(new DerbyStorageRowLocation(location), values);
            return true;
        } catch (StandardException e) {
            throw new IllegalStateException("Could not advance inherited Derby storage scan", e);
        }
    }

    @Override
    public DelosRow row() {
        if (current == null) {
            throw new IllegalStateException("Derby storage scan is not positioned on a row");
        }
        return current;
    }

    @Override
    public void close() {
        try {
            scanController.close();
        } catch (StandardException e) {
            throw new IllegalStateException("Could not close inherited Derby storage scan", e);
        } finally {
            current = null;
        }
    }
}
