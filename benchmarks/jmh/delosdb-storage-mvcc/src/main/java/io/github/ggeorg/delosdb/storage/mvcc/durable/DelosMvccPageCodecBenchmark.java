package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Arrays;
import java.util.Optional;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccPageRecordCodec;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/**
 * JMH adapter benchmark for DelosDB-owned MVCC page and overflow codecs.
 *
 * <p>This source intentionally lives under {@code benchmarks/jmh} and is not
 * compiled by normal Gradle checks. CI/release jobs may compile it with an
 * approved JMH classpath through {@code delosJmhStorageBenchmarkAdapter}.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class DelosMvccPageCodecBenchmark {
    @Param({"128", "1024", "4096"})
    public int payloadBytes;

    private byte[] payload;
    private MvccVersionRecord versionRecord;
    private byte[] encodedVersionRecord;
    private MvccOverflowPayloadChunk overflowChunk;
    private byte[] encodedOverflowChunk;

    @Setup
    public void setup() {
        payload = new byte[payloadBytes];
        Arrays.fill(payload, (byte) 7);
        MvccTupleHeader header = new MvccTupleHeader(
                new MvccRowId(1L),
                new MvccVersionId(1L),
                MvccVersionId.NONE,
                new MvccTransactionId(1L),
                MvccTransactionId.NONE,
                new MvccCommitSequence(1L),
                MvccVersionRecordFlags.NONE);
        versionRecord = new MvccVersionRecord(header, payload);
        encodedVersionRecord = MvccPageRecordCodec.encodeVersionRecord(versionRecord);
        overflowChunk = new MvccOverflowPayloadChunk(
                0,
                1,
                payload.length,
                payload,
                Optional.empty());
        encodedOverflowChunk = MvccOverflowPayloadCodec.encodeChunk(overflowChunk);
    }

    @Benchmark
    public byte[] encodePageRecord() {
        return MvccPageRecordCodec.encodeVersionRecord(versionRecord);
    }

    @Benchmark
    public MvccVersionRecord decodePageRecord() {
        return MvccPageRecordCodec.decodeVersionRecord(encodedVersionRecord);
    }

    @Benchmark
    public byte[] encodeOverflowChunk() {
        return MvccOverflowPayloadCodec.encodeChunk(overflowChunk);
    }

    @Benchmark
    public MvccOverflowPayloadChunk decodeOverflowChunk() {
        return MvccOverflowPayloadCodec.decodeChunk(encodedOverflowChunk);
    }

    @Benchmark
    public void encodeDecodeRoundTrip(Blackhole blackhole) {
        blackhole.consume(MvccPageRecordCodec.decodeVersionRecord(
                MvccPageRecordCodec.encodeVersionRecord(versionRecord)));
        blackhole.consume(MvccOverflowPayloadCodec.decodeChunk(
                MvccOverflowPayloadCodec.encodeChunk(overflowChunk)));
    }
}
