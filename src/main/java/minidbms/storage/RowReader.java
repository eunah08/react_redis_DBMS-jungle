package minidbms.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 테이블 파일에서 행을 읽는다. 읽기 위치와 버퍼를 이 객체가 따로 가진다.
 * 그래서 FileChannel을 여러 스레드가 함께 써도 서로의 읽기 위치를 건드리지 않는다. (협약 9.2)
 * FileChannel.read(buffer, offset)만 쓰고, 한 번에 큰 덩어리(chunk)를 읽어 시스템 호출 수를 줄인다.
 * endPosition 이후는 읽지 않는다. 쓰는 중인 행이나 덜 쓰인 끝부분은 보이지 않는다.
 */
final class RowReader {

    private final FileChannel channel;
    private final long endPosition;
    private final int chunkBytes;

    private byte[] chunk = new byte[0];
    private long chunkStart;
    private int chunkLength;
    private int lastRecordBytes;

    /**
     * @param channel     읽을 파일
     * @param endPosition 읽을 수 있는 끝 위치 (이 위치는 포함하지 않음)
     * @param chunkBytes  한 번에 읽을 덩어리 크기
     */
    RowReader(FileChannel channel, long endPosition, int chunkBytes) {
        this.channel = channel;
        this.endPosition = endPosition;
        this.chunkBytes = chunkBytes;
    }

    /**
     * 위치에서 행 하나를 읽는다. 읽은 뒤 lastRecordBytes()로 행의 전체 크기를 알 수 있다.
     *
     * @param schema   테이블 정의
     * @param position 행이 시작하는 위치
     * @return 행과 위치
     * @throws StorageException 위치가 행의 시작이 아니거나 내용이 손상되었을 때
     */
    StoredRow readRow(Schema schema, long position) {
        int payloadLength = readPayloadLength(position);
        ByteBuffer payload = slice(position + RowCodec.LENGTH_PREFIX_BYTES, payloadLength);
        lastRecordBytes = RowCodec.LENGTH_PREFIX_BYTES + payloadLength;
        return new StoredRow(position, RowCodec.decodeRow(schema, payload));
    }

    /**
     * @return 방금 읽은 행의 전체 크기 (길이 4바이트 포함)
     */
    int lastRecordBytes() {
        return lastRecordBytes;
    }

    /**
     * 처음부터 길이만 따라가며 온전한 행이 끝나는 위치를 찾는다.
     * 마지막 행이 덜 쓰인 파일에서는 덜 쓰인 행의 시작 위치가 나온다.
     *
     * @return 온전한 행들이 끝나는 위치
     */
    long findValidEnd() {
        long position = RowCodec.HEADER_BYTES;
        while (position + RowCodec.LENGTH_PREFIX_BYTES <= endPosition) {
            int payloadLength = slice(position, RowCodec.LENGTH_PREFIX_BYTES).getInt();
            if (!isValidLength(payloadLength, position)) {
                break;
            }
            position += RowCodec.LENGTH_PREFIX_BYTES + payloadLength;
        }
        return position;
    }

    private int readPayloadLength(long position) {
        if (position + RowCodec.LENGTH_PREFIX_BYTES > endPosition) {
            throw new StorageException("[readPayloadLength] 파일 끝을 넘는 위치입니다: " + position);
        }
        int payloadLength = slice(position, RowCodec.LENGTH_PREFIX_BYTES).getInt();
        if (!isValidLength(payloadLength, position)) {
            throw new StorageException("[readPayloadLength] 행 길이가 올바르지 않습니다 (위치 " + position + ")");
        }
        return payloadLength;
    }

    private boolean isValidLength(int payloadLength, long position) {
        return payloadLength > 0
                && payloadLength <= RowCodec.MAX_PAYLOAD_BYTES
                && position + RowCodec.LENGTH_PREFIX_BYTES + payloadLength <= endPosition;
    }

    /** 파일의 [position, position + length) 구간을 돌려준다. 덩어리 밖이면 새로 읽는다. */
    private ByteBuffer slice(long position, int length) {
        if (position < 0 || position + length > endPosition) {
            throw new StorageException("[slice] 읽을 범위가 파일 끝을 넘습니다");
        }
        boolean isInChunk = position >= chunkStart && position + length <= chunkStart + chunkLength;
        if (!isInChunk) {
            refill(position, length);
        }
        return ByteBuffer.wrap(chunk, (int) (position - chunkStart), length);
    }

    private void refill(long position, int length) {
        long available = endPosition - position;
        int size = (int) Math.min(Math.max(chunkBytes, length), available);
        if (chunk.length < size) {
            chunk = new byte[size];
        }
        readFully(ByteBuffer.wrap(chunk, 0, size), position);
        chunkStart = position;
        chunkLength = size;
    }

    private void readFully(ByteBuffer buffer, long position) {
        long current = position;
        try {
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer, current);
                if (count < 0) {
                    throw new StorageException("[readFully] 파일이 예상보다 짧습니다");
                }
                current += count;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("[readFully] 파일을 읽지 못했습니다", e);
        }
    }
}
