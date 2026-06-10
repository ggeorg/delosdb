package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque provider-neutral representation of an index key.
 *
 * <p>The encoded form is deliberately byte-based for the initial SPI so the
 * public contract does not expose Derby {@code DataValueDescriptor}, H2
 * {@code Value}, MapDB serializers, or Java object layouts. Key encoding rules
 * will be defined by DelosDB adapters/provider options as the physical index
 * bridge matures.</p>
 */
@ExperimentalSpi("Initial opaque index key representation; encoding rules are not yet finalized.")
public record IndexKey(byte[] encodedKey) {
    public IndexKey {
        Objects.requireNonNull(encodedKey, "encodedKey");
        if (encodedKey.length == 0) {
            throw new IllegalArgumentException("encodedKey must not be empty");
        }
        encodedKey = encodedKey.clone();
    }

    @Override
    public byte[] encodedKey() {
        return encodedKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndexKey that && Arrays.equals(encodedKey, that.encodedKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encodedKey);
    }

    @Override
    public String toString() {
        return "IndexKey[bytes=" + encodedKey.length + ']';
    }
}
