package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderResolver;
import io.github.ggeorg.delosdb.spi.index.IndexAccess;
import io.github.ggeorg.delosdb.spi.index.IndexAccessException;
import io.github.ggeorg.delosdb.spi.index.IndexCursor;
import io.github.ggeorg.delosdb.spi.index.IndexKey;
import io.github.ggeorg.delosdb.spi.index.IndexLookup;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexOpenRequest;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;
import io.github.ggeorg.delosdb.spi.index.RowReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proves IndexProvider v2 has two independent implementations: Derby-backed
 * btree structural access and provider-owned in-memory access.
 */
public final class IndexProviderV2Smoke {
    private IndexProviderV2Smoke() {
    }

    public static void main(String[] args) throws Exception {
        IndexProviderResolver resolver = IndexProviderResolver.builtIns();
        IndexProvider btree = resolver.requireEnabled(BuiltInExtensions.BTREE_INDEX_PROVIDER);
        IndexProvider memory = resolver.requireEnabled(BuiltInExtensions.MEMORY_INDEX_PROVIDER);

        proveBTreeStructuralAccess(btree);
        proveMemoryRuntimeAccess(memory);

        System.out.println("DelosDB IndexProvider v2 smoke test passed.");
    }

    private static void proveBTreeStructuralAccess(IndexProvider provider) throws Exception {
        IndexMetadata metadata = IndexMetadata.of(provider.name(), "btree_v2_probe", List.of("code"));
        Optional<IndexAccess> access = provider.openAccess(IndexOpenRequest.readOnly(metadata));
        if (access.isEmpty()) {
            throw new IllegalStateException("btree provider did not open structural access");
        }
        try (IndexAccess opened = access.get()) {
            try {
                opened.insert(key("001"), row("row-1"));
                throw new IllegalStateException("btree structural access unexpectedly allowed insert");
            } catch (IndexAccessException expected) {
                requireContains(expected.getMessage(), "structural only", "btree structural diagnostic");
            }
        }
    }

    private static void proveMemoryRuntimeAccess(IndexProvider provider) throws Exception {
        IndexMetadata metadata = IndexMetadata.of(provider.name(), "memory_v2_probe", List.of("code"));
        Optional<IndexAccess> access = provider.openAccess(IndexOpenRequest.create(metadata));
        if (access.isEmpty()) {
            throw new IllegalStateException("memory provider did not open provider-owned access");
        }

        try (IndexAccess opened = access.get()) {
            RowReference row1 = row("row-1");
            RowReference row2 = row("row-2");
            RowReference row3 = row("row-3");

            opened.insert(key("alpha"), row1);
            opened.insert(key("beta"), row2);
            opened.insert(key("beta"), row3);

            assertEquals(3L, opened.estimatedRowCount(), "memory estimated row count after insert");
            assertRows(List.of(row2, row3), collect(opened.find(IndexLookup.equality(key("beta")))), "memory equality lookup");
            assertRows(List.of(row1, row2, row3), collect(opened.find(
                    IndexLookup.range(key("alpha"), true, key("beta"), true))), "memory range lookup");

            opened.delete(key("beta"), row2);
            assertRows(List.of(row3), collect(opened.find(IndexLookup.equality(key("beta")))), "memory delete lookup");
            assertEquals(2L, opened.estimatedRowCount(), "memory estimated row count after delete");

            opened.truncate();
            assertEquals(0L, opened.estimatedRowCount(), "memory estimated row count after truncate");
            assertRows(List.of(), collect(opened.find(IndexLookup.fullScan())), "memory full scan after truncate");
        }
    }

    private static List<RowReference> collect(IndexCursor cursor) throws Exception {
        try (cursor) {
            List<RowReference> rows = new ArrayList<>();
            while (cursor.next()) {
                rows.add(cursor.rowReference());
            }
            return rows;
        }
    }

    private static IndexKey key(String value) {
        return new IndexKey(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RowReference row(String value) {
        return new RowReference(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRows(List<RowReference> expected, List<RowReference> actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " expected " + expected + " but was " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new IllegalStateException(label + " expected " + expected + " but was " + actual);
        }
    }

    private static void requireContains(String actual, String expected, String label) {
        if (actual == null || !actual.contains(expected)) {
            throw new IllegalStateException(label + " expected to contain '" + expected + "' but was: " + actual);
        }
    }
}
