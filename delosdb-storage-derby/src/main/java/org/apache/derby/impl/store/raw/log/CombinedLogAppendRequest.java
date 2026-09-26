/*

   Derby - Class org.apache.derby.impl.store.raw.log.CombinedLogAppendRequest

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

package org.apache.derby.impl.store.raw.log;

import java.util.concurrent.locks.LockSupport;
import org.apache.derby.shared.common.error.StandardException;

/** Reusable per-logger request used by the experimental WAL append combiner. */
final class CombinedLogAppendRequest {
    private volatile boolean complete;
    private Thread waiter;
    private byte[] frame;
    private int frameLength;
    private int logicalLength;
    private long instant;
    private StandardException failure;
    CombinedLogAppendRequest next;

    void prepare(byte[] preparedFrame, int preparedFrameLength, int length) {
        if (!complete && waiter != null) {
            throw new IllegalStateException("combined log append request is pending");
        }
        frame = preparedFrame;
        frameLength = preparedFrameLength;
        logicalLength = length;
        instant = 0L;
        failure = null;
        next = null;
        waiter = Thread.currentThread();
        complete = false;
    }

    byte[] frame() { return frame; }
    int frameLength() { return frameLength; }
    int logicalLength() { return logicalLength; }

    void complete(long appendedInstant) {
        instant = appendedInstant;
        complete = true;
        LockSupport.unpark(waiter);
    }

    void fail(StandardException exception) {
        failure = exception;
        complete = true;
        LockSupport.unpark(waiter);
    }

    long awaitCompletion() throws StandardException {
        while (!complete) {
            LockSupport.park(this);
        }
        StandardException exception = failure;
        long appendedInstant = instant;
        waiter = null;
        frame = null;
        next = null;
        failure = null;
        complete = true;
        if (exception != null) {
            throw exception;
        }
        return appendedInstant;
    }
}
