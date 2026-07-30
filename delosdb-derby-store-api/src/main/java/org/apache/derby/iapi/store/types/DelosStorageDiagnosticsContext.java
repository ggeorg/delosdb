/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit immutable context for storage diagnostics requests. */
public record DelosStorageDiagnosticsContext(
        Path databaseDirectory,
        String databaseIdentity) {
    public static final DelosStorageDiagnosticsContext EMPTY =
            new DelosStorageDiagnosticsContext(null, null);

    public DelosStorageDiagnosticsContext(Path databaseDirectory) {
        this(databaseDirectory, null);
    }

    public DelosStorageDiagnosticsContext {
        if (databaseDirectory != null && databaseIdentity != null) {
            throw new IllegalArgumentException(
                    "diagnostics context cannot name both a directory and database identity");
        }
        if (databaseIdentity != null) {
            databaseIdentity = DelosStorageText.requireNonBlank(
                databaseIdentity, "databaseIdentity");
        }
    }

    public static DelosStorageDiagnosticsContext empty() {
        return EMPTY;
    }

    public static DelosStorageDiagnosticsContext databaseDirectory(Path databaseDirectory) {
        return new DelosStorageDiagnosticsContext(
                Objects.requireNonNull(databaseDirectory, "databaseDirectory"), null);
    }

    public static DelosStorageDiagnosticsContext memoryDatabase(String databaseName) {
        String name = DelosStorageText.requireNonBlank(databaseName, "databaseName");
        try {
            String home = System.getProperty("derby.system.home");
            File database = home != null && !new File(name).isAbsolute()
                    ? new File(home, name)
                    : new File(name);
            return new DelosStorageDiagnosticsContext(
                    null,
                    "memory:" + database.getCanonicalPath());
        } catch (IOException canonicalFailure) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize memory database name " + name,
                    canonicalFailure);
        }
    }

    public static DelosStorageDiagnosticsContext fromTarget(DelosStorageConsistencyTarget target) {
        Objects.requireNonNull(target, "target");
        return target.hasDatabaseDirectory() ? databaseDirectory(target.databaseDirectory()) : EMPTY;
    }

    public boolean hasDatabaseDirectory() {
        return databaseDirectory != null;
    }

    public boolean hasDatabaseIdentity() {
        return databaseIdentity != null;
    }

}
