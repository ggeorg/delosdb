/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.shared.common.error.StandardException;

/** Test-only control for measuring MVCC internal ordered-index breadth. */
public final class MvccOrderedIndexBreadthTestSupport {
    private static final String TABLE_METADATA =
            "org.apache.derby.impl.store.access.mvcc.MvccRawStoreTableMetadata";
    private static final String ORDERED_INDEX_GENERATION =
            "org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndexGeneration";

    private MvccOrderedIndexBreadthTestSupport() {
    }

    public static int retainUniqueProbeIndexes(
            Connection connection,
            long metadataContainerId) throws SQLException {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new SQLException(
                    "Embedded connection required for MVCC ordered-index breadth experiment");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager manager)) {
            throw new SQLException(
                    "Transaction manager required for MVCC ordered-index breadth experiment");
        }

        try {
            Transaction raw = manager.getRawStoreXact();
            Object table = invokeStatic(
                    TABLE_METADATA,
                    "read",
                    raw,
                    new ContainerKey(0L, metadataContainerId));
            if (table == null) {
                throw new SQLException("MVCC table metadata is absent");
            }

            Object orderedIndexContainer = invoke(table, "orderedIndexContainer");
            if (orderedIndexContainer == null) {
                throw new SQLException("MVCC ordered-index generation is absent");
            }

            long[] btrees = (long[]) invokeStatic(
                    ORDERED_INDEX_GENERATION,
                    "requireBtreeConglomerates",
                    manager,
                    table,
                    orderedIndexContainer);
            if (btrees.length != 5) {
                throw new SQLException(
                        "Expected five MVCC candidate B-trees for benchmark fixture, got "
                                + btrees.length);
            }

            int disabled = 0;
            for (int column = 1; column < btrees.length; column++) {
                boolean usable = (Boolean) invokeStatic(
                        ORDERED_INDEX_GENERATION,
                        "isUsable",
                        btrees[column]);
                if (usable) {
                    invokeStatic(
                            ORDERED_INDEX_GENERATION,
                            "disableBtree",
                            manager,
                            table,
                            orderedIndexContainer,
                            btrees,
                            column);
                    disabled++;
                }
            }
            return disabled;
        } catch (StandardException | ReflectiveOperationException failure) {
            throw new SQLException(
                    "MVCC ordered-index breadth experiment setup failed", unwrap(failure));
        }
    }

    private static Object invoke(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name, arguments);
    }

    private static Object invokeStatic(String className, String name, Object... arguments)
            throws ReflectiveOperationException {
        return invoke(Class.forName(className), null, name, arguments);
    }

    private static Object invoke(
            Class<?> type,
            Object target,
            String name,
            Object... arguments) throws ReflectiveOperationException {
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == arguments.length
                    && (target != null || Modifier.isStatic(method.getModifiers()))) {
                if (match != null) {
                    throw new NoSuchMethodException(
                            "Ambiguous test seam " + type.getName() + "." + name);
                }
                match = method;
            }
        }
        if (match == null) {
            throw new NoSuchMethodException(
                    "Missing test seam " + type.getName() + "." + name
                            + "/" + arguments.length);
        }
        match.setAccessible(true);
        try {
            return match.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw failure;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return failure;
    }
}
