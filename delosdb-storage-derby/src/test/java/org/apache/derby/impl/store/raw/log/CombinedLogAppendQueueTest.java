/*

   Derby - Class org.apache.derby.impl.store.raw.log.CombinedLogAppendQueueTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derby.impl.store.raw.log;

/** Focused contract for reusable flat-combining request/queue semantics. */
public final class CombinedLogAppendQueueTest {
    private CombinedLogAppendQueueTest() { }

    public static void main(String[] args) throws Exception {
        CombinedLogAppendQueue queue = new CombinedLogAppendQueue();
        CombinedLogAppendRequest first = request((byte) 1);
        CombinedLogAppendRequest second = request((byte) 2);
        if (!queue.enqueue(first) || queue.enqueue(second)) {
            throw new AssertionError("combiner ownership contract failed");
        }
        CombinedLogAppendRequest batch = queue.takeBatchOrDeactivate();
        if (batch != first || batch.next != second || second.next != null) {
            throw new AssertionError("FIFO batch linkage failed");
        }
        first.next = null;
        second.next = null;
        if (queue.takeBatchOrDeactivate() != null) {
            throw new AssertionError("empty queue must deactivate combiner");
        }

        Thread completer = new Thread(() -> first.complete(1234L));
        completer.start();
        if (first.awaitCompletion() != 1234L) {
            throw new AssertionError("request completion instant mismatch");
        }
        completer.join();
        first.prepare(new byte[] { 9 }, 1, 1);
        first.complete(5678L);
        if (first.awaitCompletion() != 5678L) {
            throw new AssertionError("request reuse failed");
        }
        System.out.println("COMBINED_LOG_APPEND_QUEUE_OK");
    }

    private static CombinedLogAppendRequest request(byte value) {
        CombinedLogAppendRequest request = new CombinedLogAppendRequest();
        request.prepare(new byte[] { value }, 1, 1);
        return request;
    }
}
