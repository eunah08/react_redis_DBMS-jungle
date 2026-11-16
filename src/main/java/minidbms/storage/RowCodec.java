package minidbms.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 행 하나를 바이트로 바꾸고 되돌린다.
 *
 * <p>행 기록 형식 : [본문 길이 int32][본문][본문 CRC32 int32] (모두 big-endian).
 * 본문은 스키마 순서대로 정수는 int32, 문자열은 [UTF-8 바이트 수 int32][UTF-8 바이트]다.
 * 길이 접두 방식이라 쉼표·따옴표·줄바꿈·한글·이모지가 든 문자열도 그대로 저장된다.
 */
final class RowCodec {

    /** 본문 앞의 길이 칸 바이트 수. */
    static final int LENGTH_BYTES = 4;
    /** 본문 뒤의 CRC 칸 바이트 수. */
    static final int CRC_BYTES = 4;
    /** 이보다 긴 본문은 손상된 길이로 본다. */
    static final int MAX_PAYLOAD_BYTES = 1 << 20;

    private RowCodec() {
    }

    /**
     * 행을 [길이][본문][CRC] 기록 전체로 바꾼다.
     *
     * @param schema 테이블 정의
     * @param row    컬럼 값 목록 (id 포함, 스키마 순서). 정수는 Integer, 문자열은 String
     * @return 파일에 쓸 바이트
     */
    static byte[] encodeRecord(Schema schema, List<Object> row) {
        byte[] payload = encodePayload(schema, row);
        ByteBuffer record = ByteBuffer.allocate(LENGTH_BYTES + payload.length + CRC_BYTES);
        record.putInt(payload.length);
        record.put(payload);
        record.putInt(checksum(payload));
        return record.array();
    }

    /**
     * 본문 바이트를 행으로 되돌린다.
     *
     * @param schema  테이블 정의
     * @param payload 기록의 본문
     * @return 컬럼 값 목록
     * @throws StorageException 본문이 스키마와 맞지 않는 경우
     */
    static List<Object> decodePayload(Schema schema, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        List<Object> row = new ArrayList<>(schema.columns().size());
        for (Column column : schema.columns()) {
            row.add(column.type() == ColumnType.INT ? readInt(buffer) : readString(buffer));
        }
        if (buffer.hasRemaining()) {
            throw new StorageException("[decodePayload] 행 본문에 남는 바이트가 있습니다");
        }
        return row;
    }

    /**
     * 본문의 CRC32 값을 계산한다.
     *
     * @param payload 본문
     * @return CRC32 (하위 32비트)
     */
    static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }

    private static byte[] encodePayload(Schema schema, List<Object> row) {
        List<Column> columns = schema.columns();
        if (row.size() != columns.size()) {
            throw new IllegalArgumentException("[encodePayload] 값 개수가 컬럼 수와 다릅니다");
        }
        List<byte[]> encodedColumns = new ArrayList<>(columns.size());
        int size = 0;
        for (int i = 0; i < columns.size(); i++) {
            byte[] encoded = encodeColumn(columns.get(i), row.get(i));
            encodedColumns.add(encoded);
            size += encoded.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        encodedColumns.forEach(buffer::put);
        return buffer.array();
    }

    /** 컬럼 값 하나를 본문 조각으로 바꾼다. 정수는 int32, 문자열은 [바이트 수 int32][UTF-8 바이트]. */
    private static byte[] encodeColumn(Column column, Object value) {
        if (column.type() == ColumnType.INT) {
            return ByteBuffer.allocate(Integer.BYTES).putInt(toInt(column, value)).array();
        }
        byte[] bytes = toUtf8(column, value);
        return ByteBuffer.allocate(Integer.BYTES + bytes.length).putInt(bytes.length).put(bytes).array();
    }

    private static int toInt(Column column, Object value) {
        if (!(value instanceof Integer number)) {
            throw new IllegalArgumentException("[encodePayload] " + column.columnName() + "은 정수여야 합니다");
        }
        return number;
    }

    private static byte[] toUtf8(Column column, Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("[encodePayload] " + column.columnName() + "은 문자열이어야 합니다");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > column.maxBytes()) {
            throw new IllegalArgumentException("[encodePayload] " + column.columnName()
                    + "이 최대 " + column.maxBytes() + "바이트를 넘습니다");
        }
        return bytes;
    }

    private static int readInt(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new StorageException("[decodePayload] 행 본문이 잘려 있습니다");
        }
        return buffer.getInt();
    }

    private static String readString(ByteBuffer buffer) {
        int length = readInt(buffer);
        if (length < 0 || length > buffer.remaining()) {
            throw new StorageException("[decodePayload] 문자열 길이가 올바르지 않습니다");
        }
        String text = new String(buffer.array(), buffer.arrayOffset() + buffer.position(),
                length, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + length);
        return text;
    }
}
