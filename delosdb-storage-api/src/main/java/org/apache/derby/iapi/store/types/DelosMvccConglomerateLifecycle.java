/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle

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
package org.apache.derby.iapi.store.types;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Raw-store-owned lifecycle identity for one {@code delos_mvcc} conglomerate.
 *
 * <p>The raw log records the intended create or drop. Provider files may be
 * staged before commit, but only the raw-store transaction decides whether the
 * lifecycle becomes visible or must be cleaned up.</p>
 */
public record DelosMvccConglomerateLifecycle(
        Operation operation,
        long segmentId,
        long containerId) {
    public static final String PROVIDER_DIRECTORY = "delos_mvcc";
    public static final String INHERITED_STORE_DIRECTORY = "inherited-store";
    public static final String LIFECYCLE_DIRECTORY = "ddl-lifecycle";

    public DelosMvccConglomerateLifecycle {
        operation = Objects.requireNonNull(operation, "operation");
        if (segmentId < 0L) {
            throw new IllegalArgumentException("segmentId must be non-negative");
        }
        if (containerId <= 0L) {
            throw new IllegalArgumentException("containerId must be positive");
        }
    }

    public String storageId() {
        return "conglomerate-" + segmentId + "-" + containerId;
    }

    public String inheritedStoreRelativeDirectory() {
        return PROVIDER_DIRECTORY + "/" + INHERITED_STORE_DIRECTORY;
    }

    public String lifecycleRelativeDirectory() {
        return PROVIDER_DIRECTORY + "/" + LIFECYCLE_DIRECTORY;
    }

    public String pendingCreateMarkerName() {
        return "create-" + segmentId + "-" + containerId + ".pending";
    }

    public String committedCreateMarkerName() {
        return "create-" + segmentId + "-" + containerId + ".committed";
    }

    public String pendingCreateMarkerRelativePath() {
        return lifecycleRelativeDirectory() + "/" + pendingCreateMarkerName();
    }

    public String committedCreateMarkerRelativePath() {
        return lifecycleRelativeDirectory() + "/" + committedCreateMarkerName();
    }

    public Path inheritedStoreDirectory(Path databaseDirectory) {
        return requireDatabaseDirectory(databaseDirectory)
                .resolve(PROVIDER_DIRECTORY)
                .resolve(INHERITED_STORE_DIRECTORY);
    }

    public Path lifecycleDirectory(Path databaseDirectory) {
        return requireDatabaseDirectory(databaseDirectory)
                .resolve(PROVIDER_DIRECTORY)
                .resolve(LIFECYCLE_DIRECTORY);
    }

    public Path pendingCreateMarker(Path databaseDirectory) {
        return lifecycleDirectory(databaseDirectory).resolve(pendingCreateMarkerName());
    }

    public Path committedCreateMarker(Path databaseDirectory) {
        return lifecycleDirectory(databaseDirectory).resolve(committedCreateMarkerName());
    }

    public enum Operation {
        CREATE,
        DROP
    }

    private static Path requireDatabaseDirectory(Path databaseDirectory) {
        return Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .toAbsolutePath()
                .normalize();
    }
}
