package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.services.io.FormatIdUtil;
import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/** Durable StoreDataValue[] codec retained inside the MVCC provider module. */
final class MvccInheritedRowCodec implements PageVolumeMvccStateStore.RowCodec<StoreDataValue[]> {
    static final MvccInheritedRowCodec INSTANCE = new MvccInheritedRowCodec();

    private static final int MAGIC = 0x444D5652; // DMVR: Delos MVCC row.
    private static final int VERSION = 1;
    private static final int MAX_COLUMNS = 1_024;

    private MvccInheritedRowCodec() {
    }

    @Override
    public byte[] encode(StoreDataValue[] row) {
        StoreDataValue[] values = row == null ? new StoreDataValue[0] : row;
        if (values.length > MAX_COLUMNS) {
            throw new IllegalArgumentException("Inherited MVCC row has too many columns: " + values.length);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            StrictObjectOutput output = new StrictObjectOutput(bytes);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(values.length);
            for (StoreDataValue value : values) {
                writeColumn(output, value);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode inherited MVCC row", e);
        }
    }

    @Override
    public StoreDataValue[] decode(byte[] encoded) {
        try {
            StrictObjectInput input = new StrictObjectInput(encoded);
            int magic = input.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException("Inherited MVCC row payload does not use the typed row codec");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported inherited MVCC row codec version: " + version);
            }
            int columnCount = input.readInt();
            if (columnCount < 0 || columnCount > MAX_COLUMNS) {
                throw new IllegalStateException("Invalid inherited MVCC row column count: " + columnCount);
            }
            StoreDataValue[] row = new StoreDataValue[columnCount];
            for (int i = 0; i < columnCount; i++) {
                row[i] = readColumn(input);
            }
            return row;
        } catch (EOFException e) {
            throw new IllegalStateException("Truncated inherited MVCC row payload", e);
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Could not decode inherited MVCC row", e);
        }
    }

    private static void writeColumn(StrictObjectOutput output, StoreDataValue value) throws IOException {
        if (value == null) {
            FormatIdUtil.writeFormatIdInteger(output, StoredFormatIds.NULL_FORMAT_ID);
            return;
        }
        if (!(value instanceof Storable storable)) {
            throw new IllegalArgumentException(
                    "Inherited MVCC row value is not Derby-storable: " + value.getClass().getName());
        }
        int formatId = storable.getTypeFormatId();
        if (formatId == StoredFormatIds.SERIALIZABLE_FORMAT_ID
                || formatId == StoredFormatIds.SQL_USERTYPE_ID_V3) {
            throw new IllegalArgumentException(
                    "JAVA_OBJECT/UserType columns are not supported by the delos_mvcc durable row codec: "
                            + value.getClass().getName());
        }
        FormatIdUtil.writeFormatIdInteger(output, formatId);
        boolean isNull = storable.isNull();
        output.writeBoolean(isNull);
        if (!isNull) {
            storable.writeExternal(output);
        }
    }

    private static StoreDataValue readColumn(StrictObjectInput input) throws IOException, ClassNotFoundException {
        int formatId = FormatIdUtil.readFormatIdInteger(input);
        if (formatId == StoredFormatIds.NULL_FORMAT_ID) {
            return null;
        }
        if (formatId == StoredFormatIds.SERIALIZABLE_FORMAT_ID
                || formatId == StoredFormatIds.SQL_USERTYPE_ID_V3) {
            throw new IllegalStateException("Inherited MVCC row column uses unsupported JAVA_OBJECT/UserType format id");
        }
        Object instance;
        try {
            instance = Monitor.newInstanceFromIdentifier(formatId);
        } catch (StandardException e) {
            throw new ClassNotFoundException("Could not instantiate Derby format id " + formatId, e);
        }
        if (!(instance instanceof Storable storable)) {
            throw new IllegalStateException("Inherited MVCC row format id is not Storable: " + formatId);
        }
        if (!(instance instanceof StoreDataValue value)) {
            throw new IllegalStateException("Inherited MVCC row format id is not StoreDataValue: " + formatId);
        }
        boolean isNull = input.readBoolean();
        if (isNull) {
            storable.restoreToNull();
            return value;
        }
        storable.readExternal(input);
        return value;
    }

    private static final class StrictObjectOutput implements ObjectOutput {
        private final DataOutputStream output;

        private StrictObjectOutput(ByteArrayOutputStream bytes) {
            this.output = new DataOutputStream(bytes);
        }

        @Override
        public void writeObject(Object obj) throws IOException {
            throw new IOException("Java object serialization is not supported by the MVCC row codec");
        }

        @Override
        public void write(int b) throws IOException {
            output.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            output.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            output.write(b, off, len);
        }

        @Override
        public void writeBoolean(boolean v) throws IOException {
            output.writeBoolean(v);
        }

        @Override
        public void writeByte(int v) throws IOException {
            output.writeByte(v);
        }

        @Override
        public void writeShort(int v) throws IOException {
            output.writeShort(v);
        }

        @Override
        public void writeChar(int v) throws IOException {
            output.writeChar(v);
        }

        @Override
        public void writeInt(int v) throws IOException {
            output.writeInt(v);
        }

        @Override
        public void writeLong(long v) throws IOException {
            output.writeLong(v);
        }

        @Override
        public void writeFloat(float v) throws IOException {
            output.writeFloat(v);
        }

        @Override
        public void writeDouble(double v) throws IOException {
            output.writeDouble(v);
        }

        @Override
        public void writeBytes(String s) throws IOException {
            output.writeBytes(s);
        }

        @Override
        public void writeChars(String s) throws IOException {
            output.writeChars(s);
        }

        @Override
        public void writeUTF(String s) throws IOException {
            output.writeUTF(s);
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    private static final class StrictObjectInput implements ObjectInput {
        private final DataInputStream input;

        private StrictObjectInput(byte[] bytes) {
            this.input = new DataInputStream(new ByteArrayInputStream(bytes));
        }

        @Override
        public Object readObject() throws ClassNotFoundException {
            throw new ClassNotFoundException("Java object serialization is not supported by the MVCC row codec");
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return input.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return input.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return input.skip(n);
        }

        @Override
        public int available() throws IOException {
            return input.available();
        }

        @Override
        public void close() throws IOException {
            input.close();
        }

        @Override
        public void readFully(byte[] b) throws IOException {
            input.readFully(b);
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            input.readFully(b, off, len);
        }

        @Override
        public int skipBytes(int n) throws IOException {
            return input.skipBytes(n);
        }

        @Override
        public boolean readBoolean() throws IOException {
            return input.readBoolean();
        }

        @Override
        public byte readByte() throws IOException {
            return input.readByte();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return input.readUnsignedByte();
        }

        @Override
        public short readShort() throws IOException {
            return input.readShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            return input.readUnsignedShort();
        }

        @Override
        public char readChar() throws IOException {
            return input.readChar();
        }

        @Override
        public int readInt() throws IOException {
            return input.readInt();
        }

        @Override
        public long readLong() throws IOException {
            return input.readLong();
        }

        @Override
        public float readFloat() throws IOException {
            return input.readFloat();
        }

        @Override
        public double readDouble() throws IOException {
            return input.readDouble();
        }

        @Override
        public String readLine() throws IOException {
            throw new IOException("readLine is not supported by the MVCC row codec");
        }

        @Override
        public String readUTF() throws IOException {
            return input.readUTF();
        }
    }
}
