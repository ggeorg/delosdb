/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessItem

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
import java.util.Objects;

/** One conservative shared-service readiness decision. */
public record DelosStorageSharedServiceReadinessItem(
        String serviceName,
        DelosStorageSharedServiceReadinessLevel readinessLevel,
        boolean extractionAllowed,
        boolean readOnlyOnly,
        List<String> evidence,
        List<String> blockers,
        String nextStep) {
    public DelosStorageSharedServiceReadinessItem {
        serviceName = normalize(serviceName, "serviceName");
        readinessLevel = Objects.requireNonNull(readinessLevel, "readinessLevel");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        nextStep = normalize(nextStep, "nextStep");
        if (extractionAllowed && readinessLevel != DelosStorageSharedServiceReadinessLevel.READY_FOR_READ_ONLY_SHARED_SERVICE) {
            throw new IllegalArgumentException("only ready read-only services may be extractionAllowed");
        }
        if (extractionAllowed && !readOnlyOnly) {
            throw new IllegalArgumentException("shared-service extraction is currently limited to read-only services");
        }
    }

    public boolean readyForExtraction() {
        return extractionAllowed;
    }

    public boolean blocked() {
        return !blockers.isEmpty()
                || readinessLevel == DelosStorageSharedServiceReadinessLevel.NOT_READY
                || readinessLevel == DelosStorageSharedServiceReadinessLevel.HEAP_COMPATIBILITY_BOUNDARY;
    }

    public String summary() {
        return serviceName
                + " level=" + readinessLevel
                + " extractionAllowed=" + extractionAllowed
                + " readOnlyOnly=" + readOnlyOnly
                + " evidence=" + evidence.size()
                + " blockers=" + blockers.size()
                + " next=" + nextStep;
    }

    private static String normalize(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
