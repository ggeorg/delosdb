/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodUniqueConstraintLifecycle

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional access-method hook for transactionally maintained unique-key metadata.
 *
 * <p>The SQL layer continues to own catalog descriptors and backing indexes.
 * An access method which also needs native uniqueness metadata may implement
 * this contract on its base-table {@code ConglomerateController}. Columns are
 * zero-based positions in the base row. Implementations must treat add/drop as
 * ordinary mutations in the caller's existing store transaction.</p>
 */
public interface AccessMethodUniqueConstraintLifecycle {
    /** Validate a definition before SQL DDL creates catalog or index state. */
    void validateUniqueConstraintDefinition(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable) throws StandardException;

    /** Add one logical unique-key definition and validate existing rows. */
    void addUniqueConstraint(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable) throws StandardException;

    /** Remove one matching logical unique-key definition. */
    void dropUniqueConstraint(
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed) throws StandardException;
}
