package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH adapter benchmark for durable ordered MVCC index page lookups.
 *
 * <p>The benchmark measures existing ordered-page equality/range lookup
 * algorithms only. It must not be used to justify storage-format replacement
 * without a separate compatibility/proof slice.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class DelosMvccOrderedIndexBenchmark {
    @Param({"100", "1000", "5000"})
    public int entryCount;

    private Path tempDir;
    private MvccOrderedIndexPageStore store;
    private String equalityKey;
    private String lowerKey;
    private String upperKey;

    @Setup
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("delosdb-jmh-ordered-index-");
        store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"));
        List<MvccOrderedIndexPageStore.Entry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            entries.add(new MvccOrderedIndexPageStore.Entry(0, typedIntegerKey(i), i + 1L));
        }
        store.rewrite(entries);
        equalityKey = typedIntegerKey(entryCount / 2);
        lowerKey = typedIntegerKey(entryCount / 4);
        upperKey = typedIntegerKey((entryCount * 3) / 4);
    }

    @TearDown
    public void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        throw new IllegalStateException("could not delete JMH temp path " + path, e);
                    }
                });
            }
        }
    }

    @Benchmark
    public List<Long> equalityLookup() throws Exception {
        return store.rowIdsFor(0, equalityKey);
    }

    @Benchmark
    public List<Long> rangeLookup() throws Exception {
        return store.rowIdsInRangeFor(0, lowerKey, true, upperKey, true);
    }

    private static String typedIntegerKey(int value) {
        return "DOK1|I|" + value;
    }
}
