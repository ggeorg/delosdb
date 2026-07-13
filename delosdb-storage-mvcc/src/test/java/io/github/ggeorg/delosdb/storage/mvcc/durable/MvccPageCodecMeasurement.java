package io.github.ggeorg.delosdb.storage.mvcc.durable;

/** One low-level MVCC page/codec benchmark measurement. */
record MvccPageCodecMeasurement(
        Workload workload,
        int payloadBytes,
        int measuredOperations,
        int warmups,
        int iterations,
        long elapsedNanos,
        double throughputPerSecond,
        double averageLatencyNanos,
        long encodedBytesPerOperation,
        long allocatedBytes,
        double allocatedBytesPerOperation,
        boolean allocationMeasurementAvailable,
        long checksum,
        int run) {

    enum Workload {
        ROW_PAYLOAD_ENCODE,
        ROW_PAYLOAD_DECODE,
        VERSION_RECORD_ENCODE,
        VERSION_RECORD_DECODE,
        PAGE_RECORD_ENCODE,
        PAGE_RECORD_DECODE,
        INDEX_TUPLE_ENCODE,
        INDEX_TUPLE_DECODE,
        OVERFLOW_CHUNK_ENCODE,
        OVERFLOW_CHUNK_DECODE,
        RECOVERY_RECORD_ENCODE,
        RECOVERY_RECORD_DECODE
    }
}
