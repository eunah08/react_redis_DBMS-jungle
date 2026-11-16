package minidbms.storage;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.NoSuchElementException;

/**
 * 테이블 파일을 처음부터 끝 위치(만든 시점의 유효한 끝)까지 순서대로 읽는 반복자.
 * 읽기 전용 채널을 따로 열어 쓰기 채널과 읽기 위치를 공유하지 않는다. 한 번에 한 행만 메모리에 둔다.
 */
final class FileRowCursor implements RowCursor {

    private static final int BUFFER_BYTES = 1 << 16;

    private final Schema schema;
    private final long limit;
    private final FileChannel channel;
    private final DataInputStream input;
    private long position = TableHandle.HEADER_BYTES;
    private StoredRow nextRow;
    private boolean isClosed;

    private FileRowCursor(Schema schema, long limit, FileChannel channel) {
        this.schema = schema;
        this.limit = limit;
        this.channel = channel;
        this.input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), BUFFER_BYTES));
    }

    /**
     * 반복자를 연다.
     *
     * @param schema    테이블 정의
     * @param tablePath 테이블 파일 경로
     * @param limit     읽을 끝 위치 (이 위치 앞의 행만 읽는다)
     * @return 반복자
     * @throws StorageException 파일을 열 수 없는 경우
     */
    static FileRowCursor open(Schema schema, Path tablePath, long limit) {
        try {
            FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ);
            channel.position(TableHandle.HEADER_BYTES);
            return new FileRowCursor(schema, limit, channel);
        } catch (IOException e) {
            throw new StorageException("[readRows] 테이블 파일을 열 수 없습니다: " + tablePath.getFileName(), e);
        }
    }

    @Override
    public boolean hasNext() {
        if (nextRow == null && !isClosed) {
            nextRow = readNextRow();
        }
        return nextRow != null;
    }

    @Override
    public StoredRow next() {
        if (!hasNext()) {
            throw new NoSuchElementException("[next] 더 읽을 행이 없습니다");
        }
        StoredRow row = nextRow;
        nextRow = null;
        return row;
    }

    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("[close] 테이블 파일을 닫지 못했습니다", e);
        }
    }

    private StoredRow readNextRow() {
        if (position >= limit) {
            close();
            return null;
        }
        try {
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > RowCodec.MAX_PAYLOAD_BYTES) {
                throw new StorageException("[readRows] 행 길이가 올바르지 않습니다 (위치 " + position + ")");
            }
            byte[] payload = new byte[payloadLength];
            input.readFully(payload);
            if (input.readInt() != RowCodec.checksum(payload)) {
                throw new StorageException("[readRows] 행이 손상되었습니다 (CRC 불일치, 위치 " + position + ")");
            }
            StoredRow row = new StoredRow(position, RowCodec.decodePayload(schema, payload));
            position += RowCodec.LENGTH_BYTES + (long) payloadLength + RowCodec.CRC_BYTES;
            return row;
        } catch (IOException e) {
            close();
            throw new StorageException("[readRows] 행을 읽지 못했습니다 (위치 " + position + ")", e);
        } catch (StorageException e) {
            close();
            throw e;
        }
    }
}
