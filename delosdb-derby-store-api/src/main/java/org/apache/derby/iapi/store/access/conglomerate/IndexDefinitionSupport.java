/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.IndexDefinitionSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-provided description of the base-table columns a secondary index
 * holds. Only the data dictionary records which base column each index
 * column copies; the store asks for it where an index entry must be compared
 * with a base row.
 */
public interface IndexDefinitionSupport {
    /**
     * The base-table columns of a secondary index.
     *
     * @param baseColumnCount number of columns of the base table
     * @param baseColumns     element i is the zero-based base column that
     *                        index column i holds
     */
    record IndexColumns(int baseColumnCount, int[] baseColumns) {
    }

    /**
     * Describes the index with the given conglomerate id, as the current
     * connection's data dictionary sees it, or returns null when there is no
     * current connection or the dictionary does not describe the index.
     */
    IndexColumns indexColumns(long indexConglomerateId) throws StandardException;
}
