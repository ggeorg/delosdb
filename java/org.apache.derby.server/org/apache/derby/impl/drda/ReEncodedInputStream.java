/*
 
Derby - Class org.apache.derby.impl.drda.ReEncodedInputStream

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
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.io.OutputStreamWriter;
import java.io.Reader;

/**
 *
 * ReEncodedInputStream passes
 * stream from Reader, which is stream of decoded style, 
 * to user of this subclass of InputStream, which is stream of encoded style.
 *
 * The encoding of stream passed to user is limited to UTF8.
 *
 * This class will be used to pass stream, which is served as a Reader,
 * as a InputStream of a arbitrary encoding.
 *
 */
public class ReEncodedInputStream extends InputStream {

    private static final int BUFFERED_CHAR_LEN = 1024;
    private static final Cleaner CLEANER = Cleaner.create();


    private Reader reader_;
    private char[] decodedBuffer_;
    
    private OutputStreamWriter encodedStreamWriter_;
    private PublicBufferOutputStream encodedOutputStream_;
    
    private ByteArrayInputStream encodedInputStream_;
    private final CleanupState cleanupState_ = new CleanupState();
    private final Cleaner.Cleanable cleanable_ = CLEANER.register(this, cleanupState_);
    
    public ReEncodedInputStream(Reader reader) 
    throws IOException {
    
    reader_ = reader;
    cleanupState_.setReader(reader_);
    decodedBuffer_ = new char[BUFFERED_CHAR_LEN];

    encodedOutputStream_ = new PublicBufferOutputStream( BUFFERED_CHAR_LEN * 3 );
    encodedStreamWriter_ = new OutputStreamWriter(encodedOutputStream_,"UTF8");
    cleanupState_.setEncodedStreamWriter(encodedStreamWriter_);
    
    encodedInputStream_ = reEncode(reader_);
    cleanupState_.setEncodedInputStream(encodedInputStream_);
    
    }


    private ByteArrayInputStream reEncode(Reader reader) 
    throws IOException
    {
    
        int count;
        do{
            count = reader.read(decodedBuffer_, 0, BUFFERED_CHAR_LEN);
            
        }while(count == 0);
            
        if(count < 0)
            return null;
    
    encodedOutputStream_.reset();
    encodedStreamWriter_.write(decodedBuffer_,0,count);
    encodedStreamWriter_.flush();

    int encodedLength = encodedOutputStream_.size();
    
    return new ByteArrayInputStream(encodedOutputStream_.getBuffer(),
                    0,
                    encodedLength);
    }
    
    
    public int available() 
    throws IOException {
    
    if(encodedInputStream_ == null){
        return 0;
    }

    return encodedInputStream_.available();
    
    }
    

    public void close() 
    throws IOException {

    IOException closeFailure = cleanupState_.close();
    encodedInputStream_ = null;
    reader_ = null;
    encodedStreamWriter_ = null;
    cleanable_.clean();

    if (closeFailure != null) {
        throw closeFailure;
    }
    
    }
    
    
    public int read() 
    throws IOException {
    
    if(encodedInputStream_ == null){
        return -1;
    }
    
    int c = encodedInputStream_.read();

    if(c > -1){
        return c;
        
    }else{
        encodedInputStream_ = reEncode(reader_);
        cleanupState_.setEncodedInputStream(encodedInputStream_);
        
        if(encodedInputStream_ == null){
        return -1;
        }
        
        return encodedInputStream_.read();

    }
    
    }
    
    
    private static final class CleanupState implements Runnable {

        private Reader reader;
        private OutputStreamWriter encodedStreamWriter;
        private ByteArrayInputStream encodedInputStream;

        synchronized void setReader(Reader reader) {
            this.reader = reader;
        }

        synchronized void setEncodedStreamWriter(OutputStreamWriter writer) {
            this.encodedStreamWriter = writer;
        }

        synchronized void setEncodedInputStream(ByteArrayInputStream stream) {
            this.encodedInputStream = stream;
        }

        synchronized IOException close() {
            IOException closeFailure = null;

            if (encodedInputStream != null) {
                closeFailure = closeResource(encodedInputStream, closeFailure);
                encodedInputStream = null;
            }

            if (reader != null) {
                closeFailure = closeResource(reader, closeFailure);
                reader = null;
            }

            if (encodedStreamWriter != null) {
                closeFailure = closeResource(encodedStreamWriter, closeFailure);
                encodedStreamWriter = null;
            }

            return closeFailure;
        }

        public void run() {
            close();
        }

        private static IOException closeResource(AutoCloseable resource, IOException closeFailure) {
            try {
                resource.close();
            } catch (Exception exception) {
                IOException ioe = (exception instanceof IOException)
                    ? (IOException) exception
                    : new IOException(exception);
                if (closeFailure == null) {
                    closeFailure = ioe;
                } else {
                    closeFailure.addSuppressed(ioe);
                }
            }
            return closeFailure;
        }
    }

    private static class PublicBufferOutputStream extends ByteArrayOutputStream{
    
    PublicBufferOutputStream(int size){
        super(size);
    }

    public byte[] getBuffer(){
        return buf;
    }
    
    }

}


