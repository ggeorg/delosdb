/*

   Derby - Class org.apache.derby.client.net.NetProtocolEvidence

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

package org.apache.derby.client.net;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in DRDA client protocol counters used by DelosDB architecture-fitness
 * diagnostics.  The counters are disabled unless the JVM is started with
 * {@code -Ddelosdb.diagnostic.drdaProtocolEvidence=true}.
 *
 * <p>This is not a JDBC-facing statistics API.  It is deliberately small and
 * package-local in purpose: count authoritative protocol boundaries without
 * tracing rows or allocating per request.</p>
 */
public final class NetProtocolEvidence {

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.drdaProtocolEvidence");

    private static final AtomicLong REQUEST_FLUSHES = new AtomicLong();
    private static final AtomicLong REQUEST_BYTES = new AtomicLong();
    private static final AtomicLong REPLY_SOCKET_READS = new AtomicLong();
    private static final AtomicLong REPLY_BYTES = new AtomicLong();
    private static final AtomicLong PREPARE_COMMANDS = new AtomicLong();
    private static final AtomicLong OPEN_QUERY_COMMANDS = new AtomicLong();
    private static final AtomicLong CONTINUE_QUERY_COMMANDS = new AtomicLong();
    private static final AtomicLong EXECUTE_COMMANDS = new AtomicLong();
    private static final AtomicLong COMMIT_COMMANDS = new AtomicLong();
    private static final AtomicLong ROLLBACK_COMMANDS = new AtomicLong();
    private static final AtomicLong CLOSE_QUERY_COMMANDS = new AtomicLong();
    private static final AtomicLong QUERY_DATA_BLOCKS = new AtomicLong();
    private static final AtomicLong OPEN_QUERY_FLOW_NANOS = new AtomicLong();
    private static final AtomicLong CONTINUE_QUERY_FLOW_NANOS = new AtomicLong();
    private static final AtomicLong EXECUTE_FLOW_NANOS = new AtomicLong();
    private static final AtomicLong COMMIT_FLOW_NANOS = new AtomicLong();

    private static final int FLOW_OPEN_QUERY = 0;
    private static final int FLOW_CONTINUE_QUERY = 1;
    private static final int FLOW_EXECUTE = 2;
    private static final int FLOW_COMMIT = 3;
    private static final ThreadLocal<long[]> FLOW_STARTS = ENABLED
            ? ThreadLocal.withInitial(() -> new long[4])
            : null;
    private static volatile boolean flowTimingActive;

    private NetProtocolEvidence() {
    }

    static void requestFlushed(int bytes) {
        if (ENABLED) {
            REQUEST_FLUSHES.incrementAndGet();
            REQUEST_BYTES.addAndGet(bytes);
        }
    }

    static void replySocketRead(int bytes) {
        if (ENABLED && bytes > 0) {
            REPLY_SOCKET_READS.incrementAndGet();
            REPLY_BYTES.addAndGet(bytes);
        }
    }

    static void prepareCommand() {
        increment(PREPARE_COMMANDS);
    }

    static void beginOpenQueryFlow() {
        beginFlow(FLOW_OPEN_QUERY);
    }

    static void openQueryCommand() {
        increment(OPEN_QUERY_COMMANDS);
    }

    static void endOpenQueryFlow() {
        endFlow(FLOW_OPEN_QUERY, OPEN_QUERY_FLOW_NANOS);
    }

    static void beginContinueQueryFlow() {
        beginFlow(FLOW_CONTINUE_QUERY);
    }

    static void continueQueryCommand() {
        increment(CONTINUE_QUERY_COMMANDS);
    }

    static void endContinueQueryFlow() {
        endFlow(FLOW_CONTINUE_QUERY, CONTINUE_QUERY_FLOW_NANOS);
    }

    static void beginExecuteFlow() {
        beginFlow(FLOW_EXECUTE);
    }

    static void executeCommand() {
        increment(EXECUTE_COMMANDS);
    }

    static void endExecuteFlow() {
        endFlow(FLOW_EXECUTE, EXECUTE_FLOW_NANOS);
    }

    static void beginCommitFlow() {
        beginFlow(FLOW_COMMIT);
    }

    static void commitCommand() {
        increment(COMMIT_COMMANDS);
    }

    static void endCommitFlow() {
        endFlow(FLOW_COMMIT, COMMIT_FLOW_NANOS);
    }

    static void rollbackCommand() {
        increment(ROLLBACK_COMMANDS);
    }

    static void closeQueryCommand() {
        increment(CLOSE_QUERY_COMMANDS);
    }

    static void queryDataBlock() {
        increment(QUERY_DATA_BLOCKS);
    }

    private static void increment(AtomicLong counter) {
        if (ENABLED) {
            counter.incrementAndGet();
        }
    }

    private static void beginFlow(int flow) {
        if (!ENABLED || !flowTimingActive) {
            return;
        }
        long[] starts = FLOW_STARTS.get();
        if (starts[flow] != 0L) {
            throw new IllegalStateException("DRDA diagnostic flow already active: " + flow);
        }
        starts[flow] = System.nanoTime();
    }

    private static void endFlow(int flow, AtomicLong totalNanos) {
        if (!ENABLED || !flowTimingActive) {
            return;
        }
        long[] starts = FLOW_STARTS.get();
        long started = starts[flow];
        if (started == 0L) {
            throw new IllegalStateException("DRDA diagnostic flow was not started: " + flow);
        }
        starts[flow] = 0L;
        totalNanos.addAndGet(System.nanoTime() - started);
    }


    /** Arm flow-latency timing for the benchmark interval only. */
    public static void beginTimingWindow() {
        if (!ENABLED) {
            return;
        }
        if (flowTimingActive) {
            throw new IllegalStateException("DRDA diagnostic timing window already active");
        }
        flowTimingActive = true;
    }

    /** Disarm flow-latency timing after all measured client work has completed. */
    public static void endTimingWindow() {
        if (!ENABLED) {
            return;
        }
        if (!flowTimingActive) {
            throw new IllegalStateException("DRDA diagnostic timing window is not active");
        }
        flowTimingActive = false;
    }

    /** Reset all counters. */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        for (AtomicLong counter : counters()) {
            counter.set(0L);
        }
    }

    /**
     * Return a fixed-order snapshot for internal benchmark consumption.
     * Order: request flushes, request bytes, reply socket reads, reply bytes,
     * prepare, open-query, continue-query, execute, commit, rollback,
     * close-query, query-data blocks, open-query flow nanos, continue-query
     * flow nanos, execute flow nanos, commit flow nanos.
     */
    public static long[] snapshot() {
        AtomicLong[] counters = counters();
        long[] values = new long[counters.length];
        for (int index = 0; index < counters.length; index++) {
            values[index] = counters[index].get();
        }
        return values;
    }

    private static AtomicLong[] counters() {
        return new AtomicLong[] {
                REQUEST_FLUSHES,
                REQUEST_BYTES,
                REPLY_SOCKET_READS,
                REPLY_BYTES,
                PREPARE_COMMANDS,
                OPEN_QUERY_COMMANDS,
                CONTINUE_QUERY_COMMANDS,
                EXECUTE_COMMANDS,
                COMMIT_COMMANDS,
                ROLLBACK_COMMANDS,
                CLOSE_QUERY_COMMANDS,
                QUERY_DATA_BLOCKS,
                OPEN_QUERY_FLOW_NANOS,
                CONTINUE_QUERY_FLOW_NANOS,
                EXECUTE_FLOW_NANOS,
                COMMIT_FLOW_NANOS
        };
    }
}
