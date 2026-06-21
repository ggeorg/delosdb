package io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow execution boundary between DelosDB engine code and an opt-in
 * {@link VersionedStorageProvider}.
 *
 * <p>This is deliberately not wired to Derby SQL execution yet. Its job is to
 * keep the upcoming SQL/JDBC bridge from calling provider implementations
 * directly. The first supported operation set now covers table create/open,
 * insert, read, update, delete, scan, stats, and the first provider-owned
 * index creation plus index lookup/statistics proof. WAL and broader optimizer
 * costing remain outside this bridge until their own proofs exist.</p>
 */
@InternalApi
public final class VersionedStorageExecutionBridge {
    private static final String PROVIDER_LOOKUP_UNAVAILABLE =
            "provider lookup is unavailable on a resolved-table operation bridge";

    private final VersionedStorageProviderResolver resolver;

    public VersionedStorageExecutionBridge(VersionedStorageProviderResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    private VersionedStorageExecutionBridge() {
        this.resolver = null;
    }

    /**
     * Creates a bridge for operations that already have a resolved table.
     *
     * <p>This deliberately does not carry a provider resolver. It prevents SQL
     * bridge call sites that only need insert/scan/read/update/delete on an
     * existing {@link VersionedTable} from hiding an empty provider registry
     * behind create/open methods.</p>
     */
    public static VersionedStorageExecutionBridge resolvedTableOperations() {
        return new VersionedStorageExecutionBridge();
    }

    public <K, V> VersionedTable<K, V> createTable(String providerName, VersionedTableMetadata metadata) {
        return createTable(provider(providerName), metadata);
    }

    public <K, V> VersionedTable<K, V> createTable(
            VersionedStorageProvider provider,
            VersionedTableMetadata metadata) {
        return requireProvider(provider).createTable(requireMetadata(metadata));
    }

    public <K, V> VersionedTable<K, V> openTable(String providerName, VersionedTableMetadata metadata) {
        return openTable(provider(providerName), metadata);
    }

    public <K, V> VersionedTable<K, V> openTable(
            VersionedStorageProvider provider,
            VersionedTableMetadata metadata) {
        return requireProvider(provider).openTable(requireMetadata(metadata));
    }

    public <K, V> void insert(VersionedTable<K, V> table, K key, V value, TxContext transaction) {
        requireTable(table).insert(requireKey(key), requireValue(value), requireTransaction(transaction));
    }

    public <K, V> void update(VersionedTable<K, V> table, K key, V value, TxContext transaction) {
        requireTable(table).update(requireKey(key), requireValue(value), requireTransaction(transaction));
    }

    public <K, V> void delete(VersionedTable<K, V> table, K key, TxContext transaction) {
        requireTable(table).delete(requireKey(key), requireTransaction(transaction));
    }

    public <K, V> Optional<V> read(VersionedTable<K, V> table, K key, TxView view) {
        return requireTable(table).read(requireKey(key), requireView(view));
    }

    /**
     * Materialize the rows visible to a snapshot. A future Derby result set will
     * stream instead; the current method exists so the bridge contract can be
     * proven without coupling to Derby row objects.
     */
    public <K, V> List<VersionedRow<K, V>> scanAll(VersionedTable<K, V> table, TxView view) {
        List<VersionedRow<K, V>> rows = new ArrayList<>();
        try (VersionedScan<K, V> scan = requireTable(table).openScan(requireView(view))) {
            while (scan.next()) {
                rows.add(scan.row());
            }
        }
        return List.copyOf(rows);
    }

    public <K, V> VersionedTableStats stats(VersionedTable<K, V> table, TxView view) {
        return requireTable(table).stats(requireView(view));
    }

    public <K, V> VersionedIndex<K, V> createIndex(
            VersionedTable<K, V> table,
            VersionedIndexMetadata metadata,
            VersionedIndexKeyExtractor<V> extractor,
            TxView buildView) {
        return requireTable(table).createIndex(
                requireIndexMetadata(metadata),
                requireIndexKeyExtractor(extractor),
                requireView(buildView));
    }

    public <K, V> VersionedIndexStats indexStats(VersionedIndex<K, V> index, Object indexKey, TxView view) {
        return requireIndex(index).stats(indexKey, requireView(view));
    }

    public <K, V> List<VersionedRow<K, V>> lookup(VersionedIndex<K, V> index, Object indexKey, TxView view) {
        return materialize(requireIndex(index).lookup(indexKey, requireView(view)));
    }

    public <K, V> VersionedIndexStats indexStatsRange(
            VersionedIndex<K, V> index,
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            TxView view) {
        return requireIndex(index).statsRange(
                lowerBound,
                lowerInclusive,
                upperBound,
                upperInclusive,
                requireView(view));
    }

    public <K, V> List<VersionedRow<K, V>> lookupRange(
            VersionedIndex<K, V> index,
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            TxView view) {
        return materialize(requireIndex(index).lookupRange(
                lowerBound,
                lowerInclusive,
                upperBound,
                upperInclusive,
                requireView(view)));
    }

    public <K, V> List<VersionedRow<K, V>> lookupRange(
            VersionedIndex<K, V> index,
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            long maxRows,
            TxView view) {
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows must be non-negative");
        }
        return materialize(requireIndex(index).lookupRange(
                lowerBound,
                lowerInclusive,
                upperBound,
                upperInclusive,
                maxRows,
                requireView(view)));
    }

    private static <K, V> List<VersionedRow<K, V>> materialize(VersionedScan<K, V> scan) {
        List<VersionedRow<K, V>> rows = new ArrayList<>();
        try (VersionedScan<K, V> versionedScan = Objects.requireNonNull(scan, "scan")) {
            while (versionedScan.next()) {
                rows.add(versionedScan.row());
            }
        }
        return List.copyOf(rows);
    }

    private VersionedStorageProvider provider(String providerName) {
        if (resolver == null) {
            throw new IllegalStateException(PROVIDER_LOOKUP_UNAVAILABLE);
        }
        return resolver.requireEnabled(requireProviderName(providerName));
    }

    private static VersionedStorageProvider requireProvider(VersionedStorageProvider provider) {
        return Objects.requireNonNull(provider, "provider");
    }

    private static String requireProviderName(String providerName) {
        String trimmed = Objects.requireNonNull(providerName, "providerName").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        return trimmed;
    }

    private static VersionedTableMetadata requireMetadata(VersionedTableMetadata metadata) {
        return Objects.requireNonNull(metadata, "metadata");
    }

    private static VersionedIndexMetadata requireIndexMetadata(VersionedIndexMetadata metadata) {
        return Objects.requireNonNull(metadata, "metadata");
    }

    private static <V> VersionedIndexKeyExtractor<V> requireIndexKeyExtractor(
            VersionedIndexKeyExtractor<V> extractor) {
        return Objects.requireNonNull(extractor, "extractor");
    }

    private static <K, V> VersionedTable<K, V> requireTable(VersionedTable<K, V> table) {
        return Objects.requireNonNull(table, "table");
    }

    private static <K, V> VersionedIndex<K, V> requireIndex(VersionedIndex<K, V> index) {
        return Objects.requireNonNull(index, "index");
    }

    private static <K> K requireKey(K key) {
        return Objects.requireNonNull(key, "key");
    }

    private static <V> V requireValue(V value) {
        return Objects.requireNonNull(value, "value");
    }

    private static TxContext requireTransaction(TxContext transaction) {
        return Objects.requireNonNull(transaction, "transaction");
    }

    private static TxView requireView(TxView view) {
        return Objects.requireNonNull(view, "view");
    }
}
