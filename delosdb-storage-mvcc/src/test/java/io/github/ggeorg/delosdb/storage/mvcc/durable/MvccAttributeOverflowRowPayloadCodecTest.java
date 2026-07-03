package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

final class MvccAttributeOverflowRowPayloadCodecTest {
    @Test
    void roundTripsInlineKeyAndAttributeDescriptor() {
        MvccOverflowPayloadDescriptor descriptor = new MvccOverflowPayloadDescriptor(
                1234L,
                2,
                Optional.of(new MvccVersionLocator(new DelosPageId(7L), 3)));

        byte[] encoded = MvccAttributeOverflowRowPayloadCodec.encode("row:42", 1234L, descriptor);

        assertTrue(MvccAttributeOverflowRowPayloadCodec.isAttributeOverflowPayload(encoded));
        assertFalse(MvccRowPayloadCodec.MAGIC == MvccAttributeOverflowRowPayloadCodec.MAGIC);
        MvccAttributeOverflowRowPayloadCodec.Reference decoded =
                MvccAttributeOverflowRowPayloadCodec.decode(encoded);
        assertEquals("row:42", decoded.key());
        assertEquals(1234L, decoded.valueLength());
        assertEquals(descriptor, decoded.descriptor());
    }

    @Test
    void encodedLengthDependsOnlyOnInlineKeyAndDescriptor() {
        byte[] encoded = MvccAttributeOverflowRowPayloadCodec.encode(
                "row:1",
                1L,
                new MvccOverflowPayloadDescriptor(
                        1L,
                        1,
                        Optional.of(new MvccVersionLocator(new DelosPageId(1L), 0))));

        assertEquals(encoded.length, MvccAttributeOverflowRowPayloadCodec.encodedLengthForKey("row:1"));
    }
}
