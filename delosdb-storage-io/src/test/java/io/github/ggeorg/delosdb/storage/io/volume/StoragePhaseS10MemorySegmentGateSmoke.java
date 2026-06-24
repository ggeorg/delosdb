/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ggeorg.delosdb.storage.io.volume;

/**
 * S10 gate smoke for the MemorySegment migration decision.
 *
 * <p>This deliberately does not import java.lang.foreign.MemorySegment or Arena.
 * The migration is allowed only in a later explicit overlay after the Java
 * baseline decision is made.
 */
public final class StoragePhaseS10MemorySegmentGateSmoke {
    private StoragePhaseS10MemorySegmentGateSmoke() {
    }

    public static void main(String[] args) {
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new AssertionError("DelosDB storage I/O requires Java 21 or newer; found Java " + feature);
        }

        if (feature < 22) {
            System.out.println("storage-phase-s10-memorysegment-gate: PASS deferred on Java " + feature);
            return;
        }

        System.out.println("storage-phase-s10-memorysegment-gate: PASS future explicit migration allowed on Java " + feature);
    }
}
