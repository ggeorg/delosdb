/*

   Derby - Class org.apache.derby.impl.io.DirRandomAccessFile

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

package org.apache.derby.impl.io;

import org.apache.derby.io.StorageRandomAccessFile;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;

/**
 * Disk-based random-access storage for the directory subsubprotocol.
 *
 * <p>Ordinary {@link RandomAccessFile} operations remain available for the inherited sequential
 * log and metadata paths. RawStore page I/O uses the explicit positional methods, which delegate
 * to {@link FileChannel} without mutating the shared file pointer.</p>
 */
class DirRandomAccessFile extends RandomAccessFile implements StorageRandomAccessFile
{
    private final File name;
    private final String mode;
    private final FileChannel channel;

    /**
     * Construct a directory-backed random-access file.
     *
     * @param name file name
     * @param mode open mode: {@code r}, {@code rw}, {@code rws}, or {@code rwd}
     * @throws FileNotFoundException if the file cannot be opened
     */
    DirRandomAccessFile(File name, String mode) throws FileNotFoundException
    {
        super(name, mode);
        this.name = name;
        this.mode = mode;
        this.channel = getChannel();
    }

    /** Clone this file abstraction with an independent channel and file pointer. */
    public DirRandomAccessFile clone()
    {
        try {
            return new DirRandomAccessFile(name, mode);
        }
        catch (IOException ioe)
        {
            throw new RuntimeException(ioe.getMessage(), ioe);
        }
    }

    /**
     * Read a complete byte range using {@link FileChannel#read(ByteBuffer, long)}.
     */
    @Override
    public void readFullyAt(long position,
                            byte[] buffer,
                            int offset,
                            int length) throws IOException
    {
        checkPosition(position);
        readFully(ByteBuffer.wrap(buffer, offset, length), position);
    }

    /**
     * Write a complete byte range using {@link FileChannel#write(ByteBuffer, long)}.
     */
    @Override
    public void writeAt(long position,
                        byte[] buffer,
                        int offset,
                        int length) throws IOException
    {
        checkPosition(position);
        writeFully(ByteBuffer.wrap(buffer, offset, length), position);
    }


    /** Read a complete range from a heap or native JDK 25 memory segment. */
    @Override
    public void readFullyAt(long position,
                            MemorySegment buffer,
                            long offset,
                            long length) throws IOException
    {
        checkPosition(position);
        readFully(segmentView(buffer, offset, length, true), position);
    }

    /** Write a complete range from a heap or native JDK 25 memory segment. */
    @Override
    public void writeAt(long position,
                        MemorySegment buffer,
                        long offset,
                        long length) throws IOException
    {
        checkPosition(position);
        writeFully(segmentView(buffer, offset, length, false), position);
    }

    /** Force file contents and, when requested, file metadata. */
    @Override
    public void force(boolean metadata) throws IOException
    {
        channel.force(metadata);
    }

    /** Compatibility form of a full data-and-metadata force. */
    public void sync() throws IOException
    {
        force(true);
    }


    private void readFully(ByteBuffer target, long position)
            throws IOException
    {
        long readPosition = position;
        while (target.hasRemaining())
        {
            int count = channel.read(target, readPosition);
            if (count < 0)
            {
                throw new EOFException();
            }
            if (count == 0)
            {
                Thread.onSpinWait();
                continue;
            }
            readPosition += count;
            detectClosedByInterrupt();
        }
    }

    private void writeFully(ByteBuffer source, long position)
            throws IOException
    {
        long writePosition = position;
        try
        {
            while (source.hasRemaining())
            {
                int count = channel.write(source, writePosition);
                if (count == 0)
                {
                    Thread.onSpinWait();
                    continue;
                }
                writePosition += count;
                detectClosedByInterrupt();
            }
        }
        catch (NonWritableChannelException notWritable)
        {
            throw new IOException("Random-access file is read-only: " + name,
                    notWritable);
        }
    }

    private static ByteBuffer segmentView(
            MemorySegment buffer,
            long offset,
            long length,
            boolean writable)
    {
        if (buffer == null)
        {
            throw new NullPointerException("buffer");
        }
        if (writable && buffer.isReadOnly())
        {
            throw new IllegalArgumentException(
                    "Destination memory segment is read-only");
        }
        if (offset < 0L || length < 0L
                || offset > buffer.byteSize() - length)
        {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length
                            + ", buffer.byteSize=" + buffer.byteSize());
        }
        if (length > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "Memory-segment transfer exceeds ByteBuffer capacity: "
                            + length);
        }
        return buffer.asSlice(offset, length).asByteBuffer();
    }

    private void detectClosedByInterrupt() throws ClosedByInterruptException
    {
        if (Thread.currentThread().isInterrupted() && !channel.isOpen())
        {
            throw new ClosedByInterruptException();
        }
    }

    private static void checkPosition(long position) throws IOException
    {
        if (position < 0L)
        {
            throw new IOException("Negative position: " + position);
        }
    }
}
