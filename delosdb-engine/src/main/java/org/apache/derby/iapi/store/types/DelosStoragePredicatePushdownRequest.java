/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownRequest

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
import java.util.List;
import java.util.Objects;

/**
 * Read-only request describing a proposed storage-predicate split.
 *
 * <p>This is a metadata model only. It does not execute predicates, change Derby
 * plans, or instruct the optimizer. It lets DelosDB reason explicitly about
 * which predicate fragments could be pushed into a storage mode and which
 * fragments must remain Derby execution remainders.</p>
 */
public record DelosStoragePredicatePushdownRequest(String providerId,
                                                   Path databaseDirectory,
                                                   int segment,
                                                   long containerId,
                                                   String predicateDescription,
                                                   boolean currentCommittedRead,
                                                   boolean snapshotRead,
                                                   boolean writerBorrowedRead,
                                                   List<String> storageCandidatePredicates,
                                                   List<String> derbyRemainderPredicates) {
    public DelosStoragePredicatePushdownRequest {
        providerId = DelosStorageProviderIds.normalize(providerId);
        predicateDescription = Objects.requireNonNull(predicateDescription, "predicateDescription").trim();
        storageCandidatePredicates = normalizePredicates(storageCandidatePredicates, "storageCandidatePredicates");
        derbyRemainderPredicates = normalizePredicates(derbyRemainderPredicates, "derbyRemainderPredicates");
        if (predicateDescription.isEmpty()) {
            throw new IllegalArgumentException("predicate description must not be blank");
        }
        if (snapshotRead && currentCommittedRead) {
            throw new IllegalArgumentException("snapshot and current-committed read modes are mutually exclusive");
        }
    }

    public DelosStorageConsistencyTarget target() {
        return new DelosStorageConsistencyTarget(providerId, databaseDirectory, segment, containerId);
    }

    public boolean hasStorageCandidates() {
        return !storageCandidatePredicates.isEmpty();
    }

    public boolean hasDerbyRemainder() {
        return !derbyRemainderPredicates.isEmpty();
    }

    public String readMode() {
        if (writerBorrowedRead) {
            return "writer-borrowed";
        }
        if (snapshotRead) {
            return "snapshot";
        }
        if (currentCommittedRead) {
            return "current-committed";
        }
        return "generic";
    }

    private static List<String> normalizePredicates(List<String> predicates, String name) {
        Objects.requireNonNull(predicates, name);
        return predicates.stream()
                .map(predicate -> Objects.requireNonNull(predicate, name + " entry").trim())
                .filter(predicate -> !predicate.isEmpty())
                .toList();
    }
}
