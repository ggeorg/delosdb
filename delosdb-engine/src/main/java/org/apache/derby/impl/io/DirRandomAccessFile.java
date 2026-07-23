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
        ByteBuffer target = ByteBuffer.wrap(buffer, offset, length);
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
        ByteBuffer source = ByteBuffer.wrap(buffer, offset, length);
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
