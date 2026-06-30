/*

   Derby - Class org.apache.derby.impl.drda.DrdaExtdtaStreamMaterializer

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.derby.shared.common.reference.DRDAConstants;

/**
 * Materializes non-streamed EXTDTA values for embedded JDBC calls.
 * <p>
 * Small values stay in memory to preserve the inherited fast path. Larger
 * values are spooled to a temporary file so the DRDA server does not need to
 * hold the entire externalized value in one byte array before calling the
 * embedded statement.
 */
final class DrdaExtdtaStreamMaterializer {
    static final String SPOOL_THRESHOLD_PROPERTY =
            "delos.drda.extdta.spoolThresholdBytes";

    private static final int DEFAULT_SPOOL_THRESHOLD_BYTES = 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 32 * 1024;

    private DrdaExtdtaStreamMaterializer() {
    }

    static MaterializedExtdta materialize(EXTDTAReaderInputStream stream)
            throws IOException {
        return materialize(stream, configuredSpoolThresholdBytes());
    }

    static MaterializedExtdta materialize(
            EXTDTAReaderInputStream stream,
            int spoolThresholdBytes) throws IOException {
        if (stream == null) {
            return MaterializedExtdta.inMemory(new byte[0], 0);
        }

        int threshold = Math.max(1, spoolThresholdBytes);

        // Suppress the exception that may be thrown when reading the status
        // byte here, we want the embedded statement to fail while executing.
        stream.setSuppressException(true);

        ByteArrayOutputStream memory = null;
        OutputStream sink = null;
        Path spoolFile = null;
        long byteCount = 0L;

        try {
            long expectedLength = expectedLength(stream);
            if (expectedLength > threshold) {
                spoolFile = Files.createTempFile(
                        "delosdb-drda-extdta-", ".tmp");
                sink = Files.newOutputStream(spoolFile);
            } else {
                memory = new ByteArrayOutputStream(initialCapacity(
                        expectedLength, threshold));
                sink = memory;
            }

            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int bytesRead;
            while ((bytesRead = stream.read(buffer, 0, buffer.length)) > -1) {
                if (memory != null && byteCount + bytesRead > threshold) {
                    spoolFile = Files.createTempFile(
                            "delosdb-drda-extdta-", ".tmp");
                    OutputStream fileSink = Files.newOutputStream(spoolFile);
                    memory.writeTo(fileSink);
                    memory = null;
                    sink = fileSink;
                }
                sink.write(buffer, 0, bytesRead);
                byteCount += bytesRead;
            }
            sink.close();
            sink = null;

            if (stream.isStatusSet()
                    && stream.getStatus() != DRDAConstants.STREAM_OK) {
                deleteQuietly(spoolFile);
                return MaterializedExtdta.failing(
                        new FailingEXTDTAInputStream(stream.getStatus()),
                        byteCount);
            }

            if (spoolFile != null) {
                return MaterializedExtdta.spooled(spoolFile, byteCount);
            }

            byte[] bytes = memory.toByteArray();
            return MaterializedExtdta.inMemory(bytes, bytes.length);
        } catch (IOException | RuntimeException e) {
            closeQuietly(sink);
            deleteQuietly(spoolFile);
            throw e;
        }
    }

    private static long expectedLength(EXTDTAReaderInputStream stream)
            throws IOException {
        if (stream instanceof StandardEXTDTAReaderInputStream) {
            return ((StandardEXTDTAReaderInputStream) stream).getLength();
        }
        return 1L + stream.available();
    }

    private static int initialCapacity(long expectedLength, int threshold) {
        if (expectedLength <= 0L) {
            return Math.min(threshold, READ_BUFFER_BYTES);
        }
        return (int) Math.min((long) threshold, expectedLength);
    }

    private static int configuredSpoolThresholdBytes() {
        String value = System.getProperty(SPOOL_THRESHOLD_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_SPOOL_THRESHOLD_BYTES;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 1L) {
                return DEFAULT_SPOOL_THRESHOLD_BYTES;
            }
            return (int) Math.min(parsed, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {
            return DEFAULT_SPOOL_THRESHOLD_BYTES;
        }
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    static final class MaterializedExtdta {
        private final InputStream inputStream;
        private final long byteLength;
        private final boolean spooled;
        private final Path temporaryFile;

        private MaterializedExtdta(
                InputStream inputStream,
                long byteLength,
                boolean spooled,
                Path temporaryFile) {
            this.inputStream = inputStream;
            this.byteLength = byteLength;
            this.spooled = spooled;
            this.temporaryFile = temporaryFile;
        }

        static MaterializedExtdta inMemory(byte[] bytes, int length) {
            return new MaterializedExtdta(
                    new ByteArrayInputStream(bytes, 0, length),
                    length,
                    false,
                    null);
        }

        static MaterializedExtdta spooled(Path file, long byteLength)
                throws IOException {
            return new MaterializedExtdta(
                    new DeleteOnCloseFileInputStream(file),
                    byteLength,
                    true,
                    file);
        }

        static MaterializedExtdta failing(
                InputStream inputStream,
                long byteLength) {
            return new MaterializedExtdta(
                    inputStream,
                    byteLength,
                    false,
                    null);
        }

        InputStream inputStream() {
            return inputStream;
        }

        long byteLength() {
            return byteLength;
        }

        boolean isSpooled() {
            return spooled;
        }

        Path temporaryFileForTesting() {
            return temporaryFile;
        }
    }

    private static final class DeleteOnCloseFileInputStream extends InputStream {
        private final Path file;
        private final FileInputStream delegate;
        private boolean closed;

        DeleteOnCloseFileInputStream(Path file) throws IOException {
            this.file = file;
            this.delegate = new FileInputStream(file.toFile());
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) {
                close();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count < 0) {
                close();
            }
            return count;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public long skip(long bytes) throws IOException {
            return delegate.skip(bytes);
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                IOException closeFailure = null;
                try {
                    delegate.close();
                } catch (IOException e) {
                    closeFailure = e;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    if (closeFailure != null) {
                        closeFailure.addSuppressed(e);
                    } else {
                        closeFailure = e;
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }
    }
}
