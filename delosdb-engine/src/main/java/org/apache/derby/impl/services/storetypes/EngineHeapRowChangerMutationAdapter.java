/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.storetypes;

import java.util.Objects;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.dictionary.IndexRowGenerator;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.ExecutionFactory;
import org.apache.derby.iapi.sql.execute.RowChanger;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * N1.5 internal heap mutation adapter around Derby's RowChanger.
 *
 * <p>This class is deliberately not a DelosMutableTableAccess implementation
 * and it is deliberately not a SQL routing hook. It only proves that heap
 * INSERT / DELETE / UPDATE can be driven through one narrow internal RowChanger
 * wrapper after the N1.2 and N1.3 direct proofs.</p>
 *
 * <p>This adapter must remain internal-only. Do not wire this adapter from GenericResultSetFactory.
 * Do not treat it as heap locking parity or a generic Delos mutation contract.</p>
 */
public final class EngineHeapRowChangerMutationAdapter implements AutoCloseable {
    private final RowChanger rowChanger;
    private boolean closed;

    private EngineHeapRowChangerMutationAdapter(RowChanger rowChanger) {
        this.rowChanger = Objects.requireNonNull(rowChanger, "rowChanger");
    }

    /**
     * Open an internal RowChanger-backed heap mutation adapter.
     *
     * <p>The caller must already be inside Derby's language/execution context.
     * This method intentionally receives Derby heap mutation context explicitly;
     * it does not discover table metadata and it does not create a SQL route.</p>
     */
    public static EngineHeapRowChangerMutationAdapter open(
            ExecutionFactory executionFactory,
            long heapConglom,
            StaticCompiledOpenConglomInfo heapSCOCI,
            DynamicCompiledOpenConglomInfo heapDCOCI,
            IndexRowGenerator[] irgs,
            long[] indexCIDS,
            StaticCompiledOpenConglomInfo[] indexSCOCIs,
            DynamicCompiledOpenConglomInfo[] indexDCOCIs,
            int numberOfColumns,
            TransactionController tc,
            int[] changedColumnIds,
            FormatableBitSet baseRowReadList,
            int[] baseRowReadMap,
            int[] streamStorableColIds,
            Activation activation,
            String[] indexNames,
            int lockMode) throws StandardException {
        Objects.requireNonNull(executionFactory, "executionFactory");
        Objects.requireNonNull(tc, "tc");

        RowChanger rowChanger = executionFactory.getRowChanger(
                heapConglom,
                heapSCOCI,
                heapDCOCI,
                irgs == null ? new IndexRowGenerator[0] : irgs,
                indexCIDS == null ? new long[0] : indexCIDS,
                indexSCOCIs == null ? new StaticCompiledOpenConglomInfo[0] : indexSCOCIs,
                indexDCOCIs == null ? new DynamicCompiledOpenConglomInfo[0] : indexDCOCIs,
                numberOfColumns,
                tc,
                changedColumnIds,
                baseRowReadList,
                baseRowReadMap,
                streamStorableColIds,
                activation);
        rowChanger.setIndexNames(indexNames == null ? new String[0] : indexNames);
        rowChanger.open(lockMode);
        return new EngineHeapRowChangerMutationAdapter(rowChanger);
    }

    /** Insert a heap row through RowChanger.insertRow and return its RowLocation. */
    public RowLocation insert(ExecRow row) throws StandardException {
        ensureOpen();
        return rowChanger.insertRow(Objects.requireNonNull(row, "row"), true);
    }

    /** Update a heap row through RowChanger.updateRow. */
    public void update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)
            throws StandardException {
        ensureOpen();
        rowChanger.updateRow(
                Objects.requireNonNull(oldRow, "oldRow"),
                Objects.requireNonNull(newRow, "newRow"),
                Objects.requireNonNull(rowLocation, "rowLocation"));
    }

    /** Delete a heap row through RowChanger.deleteRow. */
    public void delete(ExecRow row, RowLocation rowLocation) throws StandardException {
        ensureOpen();
        rowChanger.deleteRow(
                Objects.requireNonNull(row, "row"),
                Objects.requireNonNull(rowLocation, "rowLocation"));
    }

    /** Finish RowChanger deferred work such as index maintenance. */
    public void finish() throws StandardException {
        ensureOpen();
        rowChanger.finish();
    }

    @Override
    public void close() throws StandardException {
        if (!closed) {
            closed = true;
            rowChanger.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("EngineHeapRowChangerMutationAdapter is closed");
        }
    }
}
