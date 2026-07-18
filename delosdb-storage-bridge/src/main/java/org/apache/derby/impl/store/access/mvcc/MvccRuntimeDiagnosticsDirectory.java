/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRuntimeDiagnosticsDirectory

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

package org.apache.derby.impl.store.access.mvcc;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-owning lookup used only by the legacy path-oriented diagnostics API.
 *
 * <p>Database lifetime is owned exclusively by the booted
 * {@link MvccConglomerateFactory}.  This directory keeps weak observations so
 * existing tests and diagnostic tools can find an already-owned runtime by
 * database directory during the RawStore convergence.  It never acquires,
 * closes, or reference-counts a runtime.</p>
 */
final class MvccRuntimeDiagnosticsDirectory {
    private static final Map<Path, WeakReference<MvccDatabaseRuntime>> RUNTIMES =
            new ConcurrentHashMap<>();

    private MvccRuntimeDiagnosticsDirectory() {
    }

    static void register(Path databaseDirectory, MvccDatabaseRuntime runtime) {
        RUNTIMES.put(normalize(databaseDirectory), new WeakReference<>(runtime));
    }

    static void unregister(Path databaseDirectory, MvccDatabaseRuntime runtime) {
        Path identity = normalize(databaseDirectory);
        RUNTIMES.computeIfPresent(identity, (ignored, reference) ->
                reference.get() == runtime ? null : reference);
    }

    static MvccDatabaseRuntime require(Path databaseDirectory) {
        MvccDatabaseRuntime runtime = runtime(databaseDirectory);
        if (runtime == null) {
            throw new IllegalStateException(
                    "No active delos_mvcc runtime for database "
                            + normalize(databaseDirectory));
        }
        return runtime;
    }

    static MvccDatabaseRuntime requireSingle() {
        List<MvccDatabaseRuntime> active = activeRuntimes();
        if (active.size() != 1) {
            throw new IllegalStateException(
                    "MVCC diagnostics require an explicit database directory when "
                            + active.size() + " database runtimes are active");
        }
        return active.get(0);
    }

    static boolean isActive(Path databaseDirectory) {
        return runtime(databaseDirectory) != null;
    }

    static int stateCount(Path databaseDirectory) {
        MvccDatabaseRuntime runtime = runtime(databaseDirectory);
        return runtime == null ? 0 : runtime.stateCount();
    }

    static int totalStateCount() {
        return activeRuntimes().stream().mapToInt(MvccDatabaseRuntime::stateCount).sum();
    }

    static int runtimeCount() {
        return activeRuntimes().size();
    }

    static void clearForTesting(Path databaseDirectory) {
        RUNTIMES.remove(normalize(databaseDirectory));
    }

    static void clearAllForTesting() {
        RUNTIMES.clear();
    }

    private static MvccDatabaseRuntime runtime(Path databaseDirectory) {
        Path identity = normalize(databaseDirectory);
        WeakReference<MvccDatabaseRuntime> reference = RUNTIMES.get(identity);
        if (reference == null) {
            return null;
        }
        MvccDatabaseRuntime runtime = reference.get();
        if (runtime == null) {
            RUNTIMES.remove(identity, reference);
        }
        return runtime;
    }

    private static List<MvccDatabaseRuntime> activeRuntimes() {
        List<MvccDatabaseRuntime> active = new ArrayList<>();
        RUNTIMES.forEach((identity, reference) -> {
            MvccDatabaseRuntime runtime = reference.get();
            if (runtime == null) {
                RUNTIMES.remove(identity, reference);
            } else {
                active.add(runtime);
            }
        });
        return active;
    }

    private static Path normalize(Path databaseDirectory) {
        Path absolute = databaseDirectory.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            return absolute;
        }
    }
}
