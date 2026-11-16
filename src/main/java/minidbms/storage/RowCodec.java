package minidbms.storage;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** 고정 길이 행 인코딩. 공유 가변 버퍼나 공유 파일 위치를 사용하지 않는다. */
final class RowCodec {
    private final Schema schema;
    private final int recordBytes;

    RowCodec(Schema schema) {
        this.schema = schema;
        recordBytes = schema.columns().stream().mapToInt(column -> Integer.BYTES
                + (column.type() == ColumnType.STRING ? column.maxBytes() : 0)).sum();
    }

    int recordBytes() { return recordBytes; }

    byte[] fingerprint() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(schema.toString()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new StorageException("fingerprint", failure);
        }
    }

    ByteBuffer encode(List<Object> row) {
        if (row.size() != schema.columns().size()) {
            throw new StorageException("appendRow", new IllegalArgumentException("컬럼 개수"));
        }
        ByteBuffer buffer = ByteBuffer.allocate(recordBytes);
        for (int index = 0; index < row.size(); index++) {
            writeValue(buffer, schema.columns().get(index), row.get(index));
        }
        buffer.flip();
        return buffer;
    }

    private void writeValue(ByteBuffer buffer, Column column, Object value) {
        if (column.type() == ColumnType.INT) {
            if (!(value instanceof Integer number) || number < 0
                    || column.columnName().equals("id") && number == 0) { throw invalidRow(); }
            buffer.putInt(number);
        } else {
            if (!(value instanceof String text)) { throw invalidRow(); }
            byte[] bytes;
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
                bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            } catch (CharacterCodingException failure) { throw invalidRow(); }
            if (bytes.length > column.maxBytes()) { throw invalidRow(); }
            buffer.putInt(bytes.length);
            int start = buffer.position();
            buffer.put(bytes);
            buffer.position(start + column.maxBytes());
        }
    }

    List<Object> decode(ByteBuffer buffer) {
        List<Object> row = new ArrayList<>(schema.columns().size());
        for (Column column : schema.columns()) {
            int value = buffer.getInt();
            if (column.type() == ColumnType.INT) {
                if (value < 0 || column.columnName().equals("id") && value == 0) { throw invalidRow(); }
                row.add(value);
            } else { row.add(readString(buffer, column, value)); }
        }
        return List.copyOf(row);
    }

    private String readString(ByteBuffer buffer, Column column, int length) {
        if (length < 0 || length > column.maxBytes()) { throw invalidRow(); }
        int start = buffer.position();
        ByteBuffer value = buffer.slice();
        value.limit(length);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(value).toString();
            buffer.position(start + column.maxBytes());
            return text;
        } catch (CharacterCodingException failure) {
            throw new StorageException("readRows", failure);
        }
    }

    private StorageException invalidRow() {
        return new StorageException("rowCodec", new IllegalArgumentException("행 형식 오류"));
    }
}
