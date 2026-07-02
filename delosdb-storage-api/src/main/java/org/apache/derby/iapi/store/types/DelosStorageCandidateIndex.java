/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCandidateIndex

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

import java.util.List;
import java.util.Optional;

/** Optional candidate-row index used by provider-aware scans to narrow equality predicates. */
public interface DelosStorageCandidateIndex {
    Optional<List<Long>> candidateRowIdsFor(int column, String value);

    /**
     * Optional ordered page-backed lookup for current-committed equality scans.
     *
     * <p>An empty optional means the ordered page sidecar cannot currently answer
     * this lookup and callers should fall back to the existing candidate index.
     * A present empty list means the ordered sidecar answered the lookup and found
     * no matching row ids.</p>
     */
    default Optional<List<Long>> orderedIndexCandidateRowIdsFor(int column, String value) {
        return Optional.empty();
    }

    int candidateIndexKeyCountForTesting();
}
