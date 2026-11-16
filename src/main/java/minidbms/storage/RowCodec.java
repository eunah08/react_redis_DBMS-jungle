package minidbms.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 행 하나를 바이트로 바꾸고 되돌린다.
 * 테이블 파일 형식 : [헤더 12바이트 = 매직 "MDBT0002" 8바이트 + 스키마 CRC32 4바이트] 뒤에 행이 이어진다.
 * 행 하나 : [내용 길이 4바이트] + [내용] + [내용 CRC32 4바이트]. 모든 정수는 빅엔디안이다.
 * 행마다 CRC를 붙여 손상된 행을 읽을 때 알아채고, 헤더의 스키마 CRC로 다른 스키마의 파일을 잘못 여는 일을 막는다.
 * 내용 : 스키마 컬럼 순서대로, 정수는 4바이트, 문자열은 [UTF-8 바이트 수 4바이트] + [UTF-8 바이트].
 * 길이를 앞에 적으므로 쉼표·따옴표·줄바꿈·한글·이모지가 들어 있어도 행 경계가 헷갈리지 않는다.
 */
final class RowCodec {

    static final byte[] MAGIC = "MDBT0002".getBytes(StandardCharsets.US_ASCII);
    static final int MAGIC_BYTES = 8;
    static final int HEADER_BYTES = MAGIC_BYTES + 4;
    static final int LENGTH_PREFIX_BYTES = 4;
    static final int CRC_BYTES = 4;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private RowCodec() {
    }

    /**
     * @param schema 테이블 정의
     * @param row    컬럼 값 목록 (id 포함, 스키마 순서). 정수는 Integer, 문자열은 String
     * @return 파일에 그대로 쓸 수 있는 [길이 + 내용 + CRC] 바이트
     * @throws StorageException 값의 개수·자료형·문자열 길이가 스키마와 맞지 않을 때
     */
    static byte[] encodeRow(Schema schema, List<Object> row) {
        if (row.size() != schema.columns().size()) {
            throw new StorageException("[encodeRow] 값 개수가 컬럼 수와 다릅니다");
        }
        byte[] payload = encodePayload(schema, row);
        ByteBuffer record = ByteBuffer.allocate(LENGTH_PREFIX_BYTES + payload.length + CRC_BYTES);
        record.putInt(payload.length);
        record.put(payload);
        record.putInt(checksum(payload));
        return record.array();
    }

    /**
     * @param payload 행의 내용 바이트
     * @return 내용의 CRC32 (하위 32비트)
     */
    static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }

    /**
     * 스키마의 컬럼 이름·자료형·최대 바이트로 만든 지문. 파일 머리에 적어 다른 스키마의 파일을 가려낸다.
     *
     * @param schema 테이블 정의
     * @return 스키마 CRC32 (하위 32비트)
     */
    static int schemaChecksum(Schema schema) {
        StringBuilder text = new StringBuilder();
        for (Column column : schema.columns()) {
            text.append(column.columnName()).append(':').append(column.type())
                    .append(':').append(column.maxBytes()).append('|');
        }
        return checksum(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encodePayload(Schema schema, List<Object> row) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            for (int i = 0; i < schema.columns().size(); i++) {
                writeValue(out, schema.columns().get(i), row.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("[encodePayload] 메모리에 쓰는 중 오류가 났습니다", e);
        }
        return bytes.toByteArray();
    }

    private static void writeValue(DataOutputStream out, Column column, Object value) throws IOException {
        if (column.type() == ColumnType.INT) {
            if (!(value instanceof Integer number)) {
                throw new StorageException("[writeValue] 정수 컬럼에 정수가 아닌 값이 있습니다: " + column.columnName());
            }
            out.writeInt(number);
            return;
        }
        byte[] textBytes = toStringBytes(column, value);
        out.writeInt(textBytes.length);
        out.write(textBytes);
    }

    private static byte[] toStringBytes(Column column, Object value) {
        if (!(value instanceof String text)) {
            throw new StorageException("[toStringBytes] 문자열 컬럼에 문자열이 아닌 값이 있습니다: " + column.columnName());
        }
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > column.maxBytes()) {
            throw new StorageException("[toStringBytes] 문자열이 최대 바이트 수를 넘습니다: " + column.columnName());
        }
        return textBytes;
    }

    /**
     * @param schema  테이블 정의
     * @param payload 행의 내용 바이트 (길이 4바이트는 이미 읽은 상태)
     * @return 컬럼 값 목록 (정수는 Integer, 문자열은 String)
     * @throws StorageException 내용이 스키마와 맞지 않거나 바이트가 모자라거나 남을 때
     */
    static List<Object> decodeRow(Schema schema, ByteBuffer payload) {
        List<Object> row = new ArrayList<>(schema.columns().size());
        for (Column column : schema.columns()) {
            row.add(readValue(column, payload));
        }
        if (payload.hasRemaining()) {
            throw new StorageException("[decodeRow] 행 끝에 남는 바이트가 있습니다 (파일 손상)");
        }
        return row;
    }

    private static Object readValue(Column column, ByteBuffer payload) {
        requireRemaining(payload, Integer.BYTES);
        int number = payload.getInt();
        if (column.type() == ColumnType.INT) {
            return number;
        }
        requireRemaining(payload, number);
        byte[] textBytes = new byte[number];
        payload.get(textBytes);
        return new String(textBytes, StandardCharsets.UTF_8);
    }

    private static void requireRemaining(ByteBuffer payload, int length) {
        if (length < 0 || payload.remaining() < length) {
            throw new StorageException("[readValue] 행 내용이 잘못되었습니다 (파일 손상)");
        }
    }
}
