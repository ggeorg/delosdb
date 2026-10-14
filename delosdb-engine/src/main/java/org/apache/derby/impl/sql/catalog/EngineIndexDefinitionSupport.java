/*

   Derby - Class org.apache.derby.impl.sql.catalog.EngineIndexDefinitionSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.sql.catalog;

import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.ConglomerateDescriptor;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.conglomerate.IndexDefinitionSupport;
import org.apache.derby.shared.common.error.StandardException;

/** Describes secondary indexes to the store from the data dictionary. */
public final class EngineIndexDefinitionSupport implements IndexDefinitionSupport {
    @Override
    public IndexColumns indexColumns(long indexConglomerateId) throws StandardException {
        LanguageConnectionContext lcc = (LanguageConnectionContext)
                ContextService.getContextOrNull(LanguageConnectionContext.CONTEXT_ID);
        if (lcc == null) {
            return null;
        }
        // Read the catalogs as compilation does, in a read-only nested
        // transaction, so the caller's transaction keeps no catalog locks.
        lcc.beginNestedTransaction(true);
        try {
            DataDictionary dd = lcc.getDataDictionary();
            ConglomerateDescriptor cd = dd.getConglomerateDescriptor(indexConglomerateId);
            if (cd == null || !cd.isIndex()) {
                return null;
            }
            TableDescriptor td = dd.getTableDescriptor(cd.getTableID());
            if (td == null) {
                return null;
            }
            int[] positions = cd.getIndexDescriptor().baseColumnPositions();
            int[] baseColumns = new int[positions.length];
            for (int column = 0; column < positions.length; column++) {
                baseColumns[column] = positions[column] - 1;
            }
            return new IndexColumns(td.getNumberOfColumns(), baseColumns);
        } finally {
            lcc.commitNestedTransaction();
        }
    }
}
