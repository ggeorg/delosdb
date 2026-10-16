/*

   Derby - Class org.apache.derby.impl.store.access.btree.index.IndexDefinitions

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.btree.index;

import java.util.ServiceLoader;

import org.apache.derby.iapi.store.access.conglomerate.IndexDefinitionSupport;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.shared.common.error.StandardException;

/** Finds the engine's index definition support for B2I conglomerates. */
final class IndexDefinitions {
    private static volatile IndexDefinitionSupport support;

    private IndexDefinitions() {
    }

    /**
     * The base-table columns of the index stored in {@code containerId}, or
     * null when no provider is present or the dictionary does not describe it.
     */
    static IndexDefinitionSupport.IndexColumns columns(
            TransactionManager xactManager, long containerId) throws StandardException {
        IndexDefinitionSupport current = support();
        return current == null
                ? null
                : current.indexColumns(xactManager.findConglomid(containerId));
    }

    /** @return the engine's support, or null if no provider is present */
    private static IndexDefinitionSupport support() {
        IndexDefinitionSupport current = support;
        if (current != null) {
            return current;
        }
        current = ServiceLoader.load(IndexDefinitionSupport.class)
                .findFirst()
                .orElseGet(IndexDefinitions::loadClasspathSupportOrNull);
        support = current;
        return current;
    }

    private static IndexDefinitionSupport loadClasspathSupportOrNull() {
        try {
            Class<?> providerClass = Class.forName(
                    "org.apache.derby.impl.sql.catalog.EngineIndexDefinitionSupport");
            return (IndexDefinitionSupport) providerClass
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
