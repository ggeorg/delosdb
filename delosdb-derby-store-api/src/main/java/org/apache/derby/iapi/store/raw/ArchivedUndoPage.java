/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.iapi.store.raw;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.shared.common.error.StandardException;

/** Optional RawStore capability for an update with an already logged archive.
 *
 * <p>The archive must have been inserted earlier by the SAME transaction. It
 * must be present and reproduce its captured bytes when undo is generated.
 * Any later changes to it must be logged in the same transaction and undone
 * first; its insertion must be undone after this update. Both pages must already
 * be latched, using no-wait acquisition for the second latch. The operation
 * changes only the target page. Redo never reads the archive. Before rollback
 * changes the target, RawStore logs a compensation record containing the full
 * reconstructed before image; compensation replay never reads the archive.</p>
 *
 * <p>A false return makes no logged change. The caller must use ordinary
 * updateAtSlot in that case (overflow, insufficient sharing, or a non-logged
 * container). An exception is not a fallback and must propagate.</p>
 */
public interface ArchivedUndoPage {
    boolean tryUpdateWithArchive(int slot, Object[] row, FormatableBitSet columns,
            Page archive, int archiveSlot) throws StandardException;
}
