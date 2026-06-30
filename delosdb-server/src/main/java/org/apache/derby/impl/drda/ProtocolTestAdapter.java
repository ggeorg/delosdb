/*

   Derby - Class org.apache.derby.impl.drda.ProtocolTestAdapter

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derby.impl.drda;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Adapter written to allow for protocol testing from the test package.
 * <p>
 * The only purpose of this class is to make certain constants and methods
 * that are package private available outside of this package for testing
 * purposes. See DERBY-2031.
 */
public class ProtocolTestAdapter {

    public static final byte SPACE = new EbcdicCcsidManager().space;
    /* Various constants we need to export. */
    public static final int CP_SQLCARD = CodePoint.SQLCARD;
    public static final int CP_SVRCOD = CodePoint.SVRCOD;
    public static final int CP_CODPNT = CodePoint.CODPNT;
    public static final int CP_PRCCNVCD = CodePoint.PRCCNVCD;
    public static final int CP_SYNERRCD = CodePoint.SYNERRCD;
    public static final int CP_MGRLVLLS = CodePoint.MGRLVLLS;
    public static final int CP_PRCCNVRM = CodePoint.PRCCNVRM;
    public static final int CP_SYNTAXRM = CodePoint.SYNTAXRM;
    public static final int CP_MGRLVLRM = CodePoint.MGRLVLRM;
    public static final int CP_SECMEC = CodePoint.SECMEC;
    public static final int CP_SECCHKCD = CodePoint.SECCHKCD;

    /** Shared code point name table (write once, then only reads/lookups). */
    private static final CodePointNameTable CP_NAMES = new CodePointNameTable();

    private final CcsidManager ccsidManager = new EbcdicCcsidManager();
    private final DDMWriter writer = new DDMWriter(null, null);
    private final Socket socket;
    private final DDMReader reader;
    private final OutputStream out;

    /**
     * Initializes the adapter for use with the given socket.
     *
     * @param socket The socket
     *
     * @throws IOException on error
     */
    public ProtocolTestAdapter(Socket socket)
            throws IOException {
        this.socket = socket;
        this.reader = new DDMReader(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * Closes the resources associated with the adapter.
     *
     * @throws IOException on error
     */
    public void close()
            throws IOException {
        // According to the JavaDoc this will also close the associated streams.
        socket.close();
    }

    /**
     * Returns the name of the given code point.
     *
     * @param codePoint code point to look up
     * @return Code point name, or {@code null} if code point is unknown.
     */
    public String lookupCodePoint(int codePoint) {
        return CP_NAMES.lookup(codePoint);
    }

    /**
     * Returns the code point id for the given code point name.
     *
     * @param codePointName the name of the code point to look up
     * @return The code point identifier, or {@code null} if the code point
     *      name is unknown.
     */
    public Integer decodeCodePoint(String codePointName) {
        return CP_NAMES.codePointForName(codePointName);
    }

    /**
     * Converts a string to a byte array according to the CCSID manager.
     *
     * @param str String to convert
     *
     * @return a byte array according to the CCSID manager.
     */
    public byte[] convertFromJavaString(String str) {
        return ccsidManager.convertFromJavaString(str);
    }

    /** Instructs the {@code DDMReader} and {@code DDMWriter} to use UTF-8. */
    public void setUtf8Ccsid() {
        writer.setUtf8Ccsid();
        reader.setUtf8Ccsid();
    }

    /* DDMWriter forwarding methods */

    public void wCreateDssRequest() {
        writer.createDssRequest();
    }

    public void wCreateDssObject() {
        writer.createDssObject();
    }

    public void wCreateDssReply() {
        writer.createDssReply();
    }

    public void wEndDss() {
        writer.endDss();
    }

    public void wEndDss(byte b) {
        writer.endDss(b);
    }

    public void wEndDdm() {
        writer.endDdm();
    }

    public void wEndDdmAndDss() {
        writer.endDdmAndDss();
    }

    public void wStartDdm(int cp) {
        writer.startDdm(cp);
    }

    public void wWriteScalarString(int cp, String str) {
        writer.writeScalarString(cp, str);
    }

    public void wWriteScalar2Bytes(int cp, int value) {
        writer.writeScalar2Bytes(cp, value);
    }

    public void wWriteScalar1Byte(int cp, int value) {
        writer.writeScalar1Byte(cp, value);
    }

    public void wWriteScalarBytes(int cp, byte[] buf) {
        writer.writeScalarBytes(cp, buf);
    }
    public void wWriteScalarPaddedBytes(int cp, byte[] buf,
                                        int length, byte ch) {
        writer.writeScalarPaddedBytes(cp, buf, length, ch);
    }

    public void wWriteByte(int b) {
        writer.writeByte(b);
    }

    public void wWriteBytes(byte[] buf) {
        writer.writeBytes(buf);
    }

    public void wWriteShort(int v) {
        writer.writeShort(v);
    }

    public void wWriteInt(int v) {
        writer.writeInt(v);
    }

    public void wWriteCodePoint4Bytes(int cp, int v) {
        writer.writeCodePoint4Bytes(cp, v);
    }

    public void wPadBytes(byte ch, int len) {
        writer.padBytes(ch, len);
    }

    public void wFlush()
            throws IOException {
        try {
            writer.finalizeChain(reader.getCurrChainState(), out);
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
        writer.reset(null);
    }

    /* DDMReader forwarding methods */

    public void rReadReplyDss()
            throws IOException {
        try {
            reader.readReplyDss();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public void rSkipDss()
            throws IOException {
        try {
            reader.readReplyDss();
            reader.skipDss();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public void rSkipDdm()
            throws IOException {
        try {
            reader.readLengthAndCodePoint(false);
            reader.skipBytes();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public void rSkipBytes()
            throws IOException {
        try {
            reader.skipBytes();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public boolean rMoreData() {
        return reader.moreData();
    }

    public boolean rMoreDssData() {
        return reader.moreDssData();
    }

    public boolean rMoreDdmData() {
        return reader.moreDssData();
    }

    public int rReadNetworkShort()
            throws IOException {
        try {
            return reader.readNetworkShort();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public byte rReadByte()
            throws IOException {
        try {
            return reader.readByte();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public byte[] rReadBytes()
            throws IOException {
        try {
            return reader.readBytes();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public int rReadLengthAndCodePoint(boolean f)
            throws IOException {
        try {
            return reader.readLengthAndCodePoint(f);
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public int rReadNetworkInt()
            throws IOException {
        try {
            return reader.readNetworkInt();
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    public String rReadString(int length, String enc)
            throws IOException {
        try {
            return reader.readString(length, enc);
        } catch (DRDAProtocolException dpe) {
            throw wrap(dpe);
        }
    }

    /* Utility methods */



    /**
     * Adapter for white-box tests of the DRDA server threading policy.
     */
    public static final class ThreadingProbe {
        public String propertyName() {
            return DrdaThreading.THREAD_MODE_PROPERTY;
        }

        public String platformModeName() {
            return DrdaThreading.THREAD_MODE_PLATFORM;
        }

        public String virtualModeName() {
            return DrdaThreading.THREAD_MODE_VIRTUAL;
        }

        public boolean usesVirtualWorkers(String propertyValue) {
            return DrdaThreading.fromPropertyValueForTesting(propertyValue)
                    .usesVirtualConnectionWorkers();
        }

        public boolean startedThreadIsVirtual(String propertyValue)
                throws Exception {
            DrdaThreading threading =
                    DrdaThreading.fromPropertyValueForTesting(propertyValue);
            final java.util.concurrent.CountDownLatch entered =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch release =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean virtual =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();

            Thread thread = threading.startThreadForTesting(
                    "drda-threading-probe",
                    () -> {
                        try {
                            virtual.set(Thread.currentThread().isVirtual());
                            entered.countDown();
                            release.await(5L, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Throwable t) {
                            failure.set(t);
                        }
                    });

            if (!entered.await(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("threading probe did not start");
            }
            release.countDown();
            thread.join(5000L);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new AssertionError("threading probe did not stop");
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            return virtual.get();
        }
    }

    /**
     * Adapter for white-box tests of the DRDA session scheduler.
     *
     * <p>The scheduler is intentionally package-private because it is an
     * implementation seam, not a public API. Tests reach it through this
     * existing protocol-test adapter instead of exporting more server internals.
     * </p>
     */
    public static final class SchedulerProbe {
        private final DrdaSessionScheduler scheduler = new DrdaSessionScheduler();
        private final java.util.Map<Integer, Session> sessions =
                new java.util.HashMap<Integer, Session>();
        private volatile boolean shutdown;

        public boolean hasIdleThreadForNewSession() {
            return scheduler.hasIdleThreadForNewSession();
        }

        public void enqueue(int connectionNumber) throws Exception {
            scheduler.enqueue(session(connectionNumber));
        }

        public int nextSessionId(Integer currentConnectionNumber)
                throws Exception {
            Session current = currentConnectionNumber == null
                    ? null
                    : session(currentConnectionNumber.intValue());
            Session next = scheduler.nextSession(current, () -> shutdown);
            return next == null ? -1 : next.getConnNum();
        }

        public int waitingSessionCount() {
            return scheduler.waitingSessionCount();
        }

        public int idleThreadCount() {
            return scheduler.idleThreadCountForTesting();
        }

        public int[] snapshotQueuedSessionIds() {
            return sessionIds(scheduler.snapshotQueuedSessions());
        }

        public int[] drainQueuedSessionIds() {
            return sessionIds(scheduler.drainQueuedSessions());
        }

        public void requestShutdown() {
            shutdown = true;
            scheduler.wakeAll();
        }

        public void wakeAll() {
            scheduler.wakeAll();
        }

        private Session session(int connectionNumber) throws Exception {
            Integer key = Integer.valueOf(connectionNumber);
            Session session = sessions.get(key);
            if (session == null) {
                session = new Session(
                        null,
                        connectionNumber,
                        new ProbeSocket(),
                        null,
                        false);
                sessions.put(key, session);
            }
            return session;
        }

        private static int[] sessionIds(java.util.List<Session> sessions) {
            int[] ids = new int[sessions.size()];
            for (int i = 0; i < sessions.size(); i++) {
                ids[i] = sessions.get(i).getConnNum();
            }
            return ids;
        }
    }

    private static final class ProbeSocket extends Socket {
        private final java.io.InputStream input =
                new java.io.ByteArrayInputStream(new byte[0]);
        private final java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();

        @Override
        public java.io.InputStream getInputStream() {
            return input;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return output;
        }
    }

    /**
     * Wraps a protocol exception in a generic I/O exception, since
     * {@code DRDAProtocolException} is package private.
     */
    private static IOException wrap(DRDAProtocolException dpe) {
        IOException ioe = new IOException(dpe.getMessage());
        ioe.initCause(dpe);
        return ioe;
    }
}
