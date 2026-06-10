package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque provider-neutral reference to a base-table row.
 *
 * <p>The encoded form is intentionally not a Derby {@code RowLocation}. Derby
 * adapters may encode row locations into this shape internally, while external
 * providers only see stable bytes governed by DelosDB's bridge contract.</p>
 */
@ExperimentalSpi("Initial opaque row reference representation; stability rules are bridge-owned.")
public record RowReference(byte[] encodedLocation) {
    public RowReference {
        Objects.requireNonNull(encodedLocation, "encodedLocation");
        if (encodedLocation.length == 0) {
            throw new IllegalArgumentException("encodedLocation must not be empty");
        }
        encodedLocation = encodedLocation.clone();
    }

    @Override
    public byte[] encodedLocation() {
        return encodedLocation.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RowReference that && Arrays.equals(encodedLocation, that.encodedLocation);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encodedLocation);
    }

    @Override
    public String toString() {
        return "RowReference[bytes=" + encodedLocation.length + ']';
    }
}
