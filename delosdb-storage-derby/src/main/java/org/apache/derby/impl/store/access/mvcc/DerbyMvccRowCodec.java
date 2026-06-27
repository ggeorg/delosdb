/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.DerbyMvccRowCodec

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

package org.apache.derby.impl.store.access.mvcc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.StoreDataValue;

/** Derby store-value codec for page-volume MVCC rows. */
final class DerbyMvccRowCodec implements PageVolumeMvccStateStore.RowCodec<StoreDataValue[]> {
    static final DerbyMvccRowCodec INSTANCE = new DerbyMvccRowCodec();

    private DerbyMvccRowCodec() {
    }

    @Override
    public byte[] encode(StoreDataValue[] values) throws IOException {
        StoreDataValue[] row = values == null ? new StoreDataValue[0] : values;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(row.length);
            for (StoreDataValue value : row) {
                writeValue(out, value);
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public StoreDataValue[] decode(byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int columnCount = in.readInt();
            if (columnCount < 0) {
                throw new IOException("negative inherited MVCC page-volume column count: " + columnCount);
            }
            StoreDataValue[] row = new StoreDataValue[columnCount];
            for (int column = 0; column < columnCount; column++) {
                row[column] = readValue(in);
            }
            return row;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static void writeValue(DataOutputStream out, StoreDataValue value) throws IOException {
        out.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        out.writeUTF(value.getClass().getName());
        byte[] encoded = encodeExternalValue(value);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static StoreDataValue readValue(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        String className = in.readUTF();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative inherited MVCC page-volume value length for " + className + ": " + length);
        }
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Short inherited MVCC page-volume value read for " + className);
        }
        return decodeExternalValue(className, encoded);
    }

    private static byte[] encodeExternalValue(StoreDataValue value) throws IOException {
        try {
            Method writeExternal = value.getClass().getMethod("writeExternal", ObjectOutput.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                writeExternal.invoke(value, out);
            }
            return bytes.toByteArray();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Inherited MVCC page-volume persistence requires externalizable store value: "
                    + value.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value writer: " + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw unwrapIoOrRuntime(e);
        }
    }

    private static StoreDataValue decodeExternalValue(String className, byte[] encoded) throws IOException {
        try {
            Class<?> valueClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            Constructor<?> constructor = valueClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            if (!(instance instanceof StoreDataValue storeValue)) {
                throw new IllegalStateException("Inherited MVCC page-volume value is not a StoreDataValue: " + className);
            }
            Method readExternal = valueClass.getMethod("readExternal", ObjectInput.class);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(encoded))) {
                readExternal.invoke(storeValue, in);
            }
            return storeValue;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot restore inherited MVCC page-volume store value: " + className, e);
        } catch (InvocationTargetException e) {
            throw unwrapIoOrRuntime(e);
        }
    }

    private static IOException unwrapIoOrRuntime(InvocationTargetException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        if (cause instanceof UncheckedIOException uncheckedIOException) {
            return uncheckedIOException.getCause();
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(cause);
    }
}
