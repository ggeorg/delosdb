/*

   Derby - Class org.apache.derby.iapi.sql.execute.ExecRowBuilder

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

package org.apache.derby.iapi.sql.execute;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.util.ArrayUtil;
import org.apache.derby.iapi.services.io.Formatable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.sanity.SanityManager;
import org.apache.derby.iapi.types.DataTypeDescriptor;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge;

/**
 * <p>
 * A class used for storing information on how to build {@code ExecRow}
 * instances. Typically created by the compiler and used during execution
 * to produce and reset row templates.
 * </p>
 *
 * <p>
 * This class must be {@code Formatable} so that it can be stored in the
 * database as part of a stored prepared statement generated for trigger
 * actions or metadata queries. The stored format does not need to be stable
 * across different versions, since the stored prepared statements are
 * discarded on upgrade and will never be read by other Derby versions than
 * the one that originally wrote them.
 * </p>
 */
public class ExecRowBuilder implements Formatable {

    /** If true, the row should be an {@code ExecIndexRow}. */
    private boolean indexable;

    /**
     * Array of templates used for creating NULL values to put in the row.
     * The templates are usually {@code DataValueDescriptor}s or
     * {@code DataTypeDescriptor}s. Store-native row locations are accepted at
     * the B6 boundary and adapted back to the inherited SQL row-location
     * surface when the row is built.
     */
    private Object[] template;

    /** Array of 1-based column numbers for the columns to access. */
    private int[] columns;

    /** The number of columns to set in the row. */
    private int count;

    /** The highest column number in the row. */
    private int maxColumnNumber;

    /**
     * Create an instance that produces an {@code ExecRow} instance of
     * the specified size.
     *
     * @param size the number of columns to initialize in the produced row
     * @param indexable {@code true} if the returned row should be an
     * {@code ExecIndexRow}, {@code false} otherwise
     */
    public ExecRowBuilder(int size, boolean indexable) {
        this.template = new Object[size];
        this.columns = new int[size];
        this.indexable = indexable;
    }

    /**
     * Public no-arg constructor required by the {@code Formatable} interface.
     * Should not be called directly.
     */
    public ExecRowBuilder() {
    }

    /**
     * Add a template from which a NULL value of the correct type can be
     * created. It should either be a {@code DataValueDescriptor}, a
     * {@code DataTypeDescriptor}, or a store-native row location that can be
     * adapted to a SQL {@code RowLocation}.
     *
     * @param column the column number
     * @param columnTemplate a template from which a NULL value can be created
     * (usually a {@code DataValueDescriptor} or a {@code DataTypeDescriptor})
     */
    public void setColumn(int column, Object columnTemplate) {
        if (SanityManager.DEBUG &&
                !(columnTemplate instanceof DataTypeDescriptor) &&
                !(columnTemplate instanceof DataValueDescriptor) &&
                !(columnTemplate instanceof StoreRowLocation)) {
            SanityManager.THROWASSERT(
                "Expected DataTypeDescriptor, DataValueDescriptor, or StoreRowLocation. Got: " +
                ((columnTemplate == null) ? columnTemplate :
                    columnTemplate.getClass().getName()));
        }
        template[count] = columnTemplate;
        columns[count] = column;
        count++;
        maxColumnNumber = Math.max(maxColumnNumber, column);
    }

    /**
     * Build a new {@code ExecRow} instance with the columns specified by
     * the {@link #setColumn(int, Object)} method initialized to empty (NULL)
     * values.
     *
     * @param ef an execution factory used to create a row
     * @return a row initialized with NULL values of the requested types
     */
    public ExecRow build(ExecutionFactory ef) throws StandardException {
        ExecRow row = indexable ?
                ef.getIndexableRow(maxColumnNumber) :
                ef.getValueRow(maxColumnNumber);

        for (int i = 0; i < count; i++) {
            Object o = template[i];
            DataValueDescriptor dvd = newNullValue(o);
            row.setColumn(columns[i], dvd);
        }

        return row;
    }

    /**
     * Create a SQL execution NULL from a template. Store-native row locations
     * may appear in stored or generated row templates after the Derby store
     * boundary moved to StoreRowLocation; adapt them before execution rows see
     * them.
     */
    private static DataValueDescriptor newNullValue(Object template)
            throws StandardException {
        if (template instanceof DataValueDescriptor) {
            return ((DataValueDescriptor) template).getNewNull();
        }
        if (template instanceof StoreRowLocation) {
            return EngineStoreRowLocationBridge
                    .requireEngineRowLocation(template)
                    .getNewNull();
        }
        return ((DataTypeDescriptor) template).getNull();
    }

    /**
     * Reset a row by creating fresh NULL values.
     * @param row the row to reset
     */
    public void reset(ExecRow row) throws StandardException {
        for (int i = 0; i < count; i++) {
            int col = columns[i];
            row.setColumn(col, row.getColumn(col).getNewNull());
        }
    }

    // Methods required by the Formatable interface.

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeBoolean(indexable);
        ArrayUtil.writeArray(out, template);
        out.writeObject(columns);
        out.writeInt(count);
        out.writeInt(maxColumnNumber);
    }

    @Override
    public void readExternal(ObjectInput in)
            throws IOException, ClassNotFoundException {
        indexable = in.readBoolean();
        template = ArrayUtil.readObjectArray(in);
        columns = (int[]) in.readObject();
        count = in.readInt();
        maxColumnNumber = in.readInt();
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.EXEC_ROW_BUILDER_ID;
    }
}
