/*

   Derby - Class org.apache.derby.io.StorageRandomAccessFile

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

package org.apache.derby.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * This interface abstracts an object that implements reading and writing on a random access
 * file. It extends DataInput and DataOutput, so it implicitly contains all the methods of those
 * interfaces. Any method in this interface that also appears in the java.io.RandomAccessFile class
 * should behave as the java.io.RandomAccessFile method does.
 *<p>
 * Each StorageRandomAccessFile has an associated file pointer, a byte offset in the file. Ordinary
 * reading and writing takes place at the file pointer offset and advances it. Positional operations
 * do not change the associated file pointer.
 *<p>
 * An implementation of StorageRandomAccessFile need not make pointer-based operations thread safe.
 * The database engine externally serializes ordinary operations that read or change the associated
 * file pointer. Implementations which override the positional methods with true positional I/O may
 * support concurrent operations on independent ranges. The pointer-preserving compatibility defaults
 * must remain externally serialized with all other pointer-based access to the same instance.
 *<p>
 * @see <a href="http://java.sun.com/j2se/1.4.2/docs/api/java/io/RandomAccessFile.html">java.io.RandomAccessFile</a>
 */
public interface StorageRandomAccessFile extends DataInput, DataOutput
{

    /**
     * Closes this file.
     *
     * @exception IOException if an I/O error occurs.
     */
    void close() throws IOException;

    /**
     * Get the current offset in this file.
     *
     * @return the current file pointer.
     * @exception IOException if an I/O error occurs.
     */
    long getFilePointer() throws IOException;

    /**
     * Gets the length of this file.
     *
     * @return the number of bytes in this file.
     * @exception IOException if an I/O error occurs.
     */
    long length() throws IOException;

    /**
     * Set the file pointer. It may be moved beyond the end of the file, but this does not change
     * the length of the file. The length of the file is not changed until data is actually written.
     *
     * @param newFilePointer the new file pointer, measured in bytes from the beginning of the file
     * @exception IOException if {@code newFilePointer} is less than zero or an I/O error occurs
     */
    void seek(long newFilePointer) throws IOException;

    /**
     * Sets the length of this file, either extending or truncating it.
     *<p>
     * If the file is extended then the contents of the extension are not defined.
     * If the file is truncated and the file pointer is greater than the new length then the file
     * pointer is set to the new length.
     *
     * @param newLength the new file length
     * @exception IOException if an I/O error occurs
     */
    void setLength(long newLength) throws IOException;

    /**
     * Force file contents and metadata out to persistent storage. Transient storage implementations
     * may implement this as a no-op.
     *
     * @exception java.io.SyncFailedException if a possibly recoverable error occurs
     * @exception IOException if an I/O error occurs
     */
    void sync() throws IOException;

    /**
     * Force changes out to persistent storage with an explicit metadata requirement.
     *
     * @param metadata {@code true} when both file contents and metadata must be forced;
     *                 {@code false} when forcing file contents is sufficient
     * @exception IOException if an I/O error occurs
     */
    default void force(boolean metadata) throws IOException {
        sync();
    }

    /**
     * Reads exactly {@code len} bytes from {@code position} without changing the file pointer.
     * Implementations backed by positional I/O should override this method. The compatibility
     * implementation preserves the current file pointer around the ordinary DataInput operation.
     *
     * @param position absolute byte position at which reading starts
     * @param buffer destination buffer
     * @param offset first destination index
     * @param length number of bytes to read
     * @exception IOException if the position is negative, EOF is reached, or an I/O error occurs
     */
    default void readFullyAt(long position,
                             byte[] buffer,
                             int offset,
                             int length) throws IOException {
        checkPosition(position);
        checkRange(buffer, offset, length);
        long originalPosition = getFilePointer();
        Throwable failure = null;
        try {
            seek(position);
            readFully(buffer, offset, length);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            restorePosition(originalPosition, failure);
        }
    }

    /**
     * Writes exactly {@code length} bytes at {@code position} without changing the file pointer.
     * Implementations backed by positional I/O should override this method. The compatibility
     * implementation preserves the current file pointer around the ordinary DataOutput operation.
     *
     * @param position absolute byte position at which writing starts
     * @param buffer source buffer
     * @param offset first source index
     * @param length number of bytes to write
     * @exception IOException if the position is negative or an I/O error occurs
     */
    default void writeAt(long position,
                         byte[] buffer,
                         int offset,
                         int length) throws IOException {
        checkPosition(position);
        checkRange(buffer, offset, length);
        long originalPosition = getFilePointer();
        Throwable failure = null;
        try {
            seek(position);
            write(buffer, offset, length);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            restorePosition(originalPosition, failure);
        }
    }

    /**
     * Reads exactly {@code length} bytes into a heap-backed memory segment without changing the
     * file pointer. Stage 8.4 deliberately accepts only heap-backed segments so page-cache
     * ownership remains with the inherited byte array. Native and mapped segment ownership is a
     * later storage decision.
     *
     * @param position absolute byte position at which reading starts
     * @param buffer writable heap-backed destination segment
     * @param offset first destination byte within the segment
     * @param length number of bytes to read
     * @exception IOException if the position is negative, EOF is reached, or an I/O error occurs
     */
    default void readFullyAt(long position,
                             MemorySegment buffer,
                             long offset,
                             long length) throws IOException {
        checkPosition(position);
        ByteBuffer target = heapSegmentView(buffer, offset, length, true);
        readFullyAt(position, target.array(),
                Math.addExact(target.arrayOffset(), target.position()),
                target.remaining());
    }

    /**
     * Writes exactly {@code length} bytes from a heap-backed memory segment without changing the
     * file pointer. Stage 8.4 deliberately accepts only heap-backed segments so page-cache
     * ownership remains with the inherited byte array.
     *
     * @param position absolute byte position at which writing starts
     * @param buffer heap-backed source segment
     * @param offset first source byte within the segment
     * @param length number of bytes to write
     * @exception IOException if the position is negative or an I/O error occurs
     */
    default void writeAt(long position,
                         MemorySegment buffer,
                         long offset,
                         long length) throws IOException {
        checkPosition(position);
        ByteBuffer source = heapSegmentView(buffer, offset, length, false);
        writeAt(position, source.array(),
                Math.addExact(source.arrayOffset(), source.position()),
                source.remaining());
    }

    /**
     * Reads up to {@code len} bytes of data from this file into an array of bytes.
     *
     * @param b the buffer into which the data is read
     * @param off the start offset in {@code b}
     * @param len the maximum number of bytes read
     * @return the total number of bytes read, or {@code -1} at end of file
     * @exception IOException if an I/O error occurs
     */
    int read(byte[] b, int off, int len) throws IOException;

    /** Clone this file abstraction. */
    StorageRandomAccessFile clone();

    private static void checkPosition(long position) throws IOException {
        if (position < 0L) {
            throw new IOException("Negative position: " + position);
        }
    }

    private static void checkRange(byte[] buffer, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length
                            + ", buffer.length=" + buffer.length);
        }
    }

    private static ByteBuffer heapSegmentView(
            MemorySegment buffer,
            long offset,
            long length,
            boolean writable) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.isNative()) {
            throw new IllegalArgumentException(
                    "Stage 8.4 accepts heap-backed memory segments only");
        }
        if (writable && buffer.isReadOnly()) {
            throw new IllegalArgumentException(
                    "Destination memory segment is read-only");
        }
        if (offset < 0L || length < 0L
                || offset > buffer.byteSize() - length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length
                            + ", buffer.byteSize=" + buffer.byteSize());
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Memory-segment transfer exceeds ByteBuffer capacity: " + length);
        }
        ByteBuffer view = buffer.asSlice(offset, length).asByteBuffer();
        if (!view.hasArray()) {
            throw new IllegalArgumentException(
                    "Heap-backed memory segment did not expose its byte array");
        }
        return view;
    }

    private void restorePosition(long originalPosition, Throwable failure)
            throws IOException {
        try {
            seek(originalPosition);
        } catch (IOException restoreFailure) {
            if (failure == null) {
                throw restoreFailure;
            }
            failure.addSuppressed(restoreFailure);
        }
    }
}
