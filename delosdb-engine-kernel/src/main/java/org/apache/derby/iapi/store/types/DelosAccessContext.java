/*

   Derby - Class org.apache.derby.iapi.store.types.DelosAccessContext

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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Execution context made available at the table-access boundary.
 *
 * <p>The boolean physical-access gate is universal.  Provider-specific Derby,
 * MVCC, snapshot, transaction, locking, or visibility objects are looked up by
 * typed keys, mirroring Derby's existing context-key idiom without importing
 * engine implementation classes into this contract package.</p>
 */
public interface DelosAccessContext {
    boolean physicalAccessAllowed();

    <T> Optional<T> find(DelosContextKey<T> key);

    default <T> T require(DelosContextKey<T> key) {
        return find(key).orElseThrow(() ->
                new NoSuchElementException("Missing Delos access context key: " + key.name()));
    }

    static DelosAccessContext empty(boolean physicalAccessAllowed) {
        return new BasicDelosAccessContext(physicalAccessAllowed, Map.of());
    }

    static Builder builder(boolean physicalAccessAllowed) {
        return new Builder(physicalAccessAllowed);
    }

    final class Builder {
        private final boolean physicalAccessAllowed;
        private final Map<DelosContextKey<?>, Object> values = new LinkedHashMap<>();

        private Builder(boolean physicalAccessAllowed) {
            this.physicalAccessAllowed = physicalAccessAllowed;
        }

        public <T> Builder put(DelosContextKey<T> key, T value) {
            values.put(Objects.requireNonNull(key, "key"), key.cast(value));
            return this;
        }

        public DelosAccessContext build() {
            return new BasicDelosAccessContext(physicalAccessAllowed, values);
        }
    }
}

final class BasicDelosAccessContext implements DelosAccessContext {
    private final boolean physicalAccessAllowed;
    private final Map<DelosContextKey<?>, Object> values;

    BasicDelosAccessContext(boolean physicalAccessAllowed, Map<DelosContextKey<?>, Object> values) {
        this.physicalAccessAllowed = physicalAccessAllowed;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public boolean physicalAccessAllowed() {
        return physicalAccessAllowed;
    }

    @Override
    public <T> Optional<T> find(DelosContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(key.cast(value));
    }
}
