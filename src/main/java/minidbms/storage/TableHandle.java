package minidbms.storage;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * 열려 있는 테이블 파일 하나. 스키마, 파일 채널, 유효한 끝 위치를 가진다.
 *
 * <p>파일 머리 : [매직 "MDB1" 4바이트][컬럼 수 int32][스키마 CRC32 int32] = 12바이트.
 * 머리가 스키마와 다르면 다른 스키마로 만든 파일이므로 열지 않는다.
 * 끝 위치(endPosition) 뒤의 덜 쓰인 행은 열 때 잘라낸다.
 */
final class TableHandle {

    static final int HEADER_BYTES = 12;
    private static final int MAGIC = 0x4D444231;
    private static final int SCAN_BUFFER_BYTES = 1 << 16;

    private final Schema schema;
    private final Path tablePath;
    private final FileChannel channel;
    private volatile long endPosition;

    private TableHandle(Schema schema, Path tablePath, FileChannel channel) {
        this.schema = schema;
        this.tablePath = tablePath;
        this.channel = channel;
    }

    /**
     * 테이블 파일을 연다. 없으면 머리만 가진 새 파일을 만든다.
     *
     * @param schema    테이블 정의
     * @param tablePath 테이블 파일 경로
     * @return 열린 핸들
     * @throws StorageException 머리가 손상됐거나 스키마와 다른 경우, 입출력 실패
     */
    static TableHandle open(Schema schema, Path tablePath) {
        try {
            FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            TableHandle handle = new TableHandle(schema, tablePath, channel);
            try {
                handle.prepareFile();
            } catch (RuntimeException | IOException exception) {
                channel.close();
                throw exception;
            }
            return handle;
        } catch (IOException exception) {
            throw new StorageException("[open] 테이블 파일을 열 수 없습니다: " + tablePath.getFileName(), exception);
        }
    }

    Schema schema() {
        return schema;
    }

    Path tablePath() {
        return tablePath;
    }

    FileChannel channel() {
        return channel;
    }

    long endPosition() {
        return endPosition;
    }

    void setEndPosition(long position) {
        this.endPosition = position;
    }

    void close() {
        try {
            channel.close();
        } catch (IOException exception) {
            throw new StorageException("[close] 테이블 파일을 닫지 못했습니다: " + tablePath.getFileName(), exception);
        }
    }

    private void prepareFile() throws IOException {
        if (channel.size() == 0) {
            writeHeader();
            endPosition = HEADER_BYTES;
            return;
        }
        checkHeader();
        endPosition = findValidEnd();
        if (endPosition < channel.size()) {
            long removedBytes = channel.size() - endPosition;
            channel.truncate(endPosition);
            System.err.println("[open] " + tablePath.getFileName() + " 마지막 행이 덜 쓰여 있어 "
                    + removedBytes + "바이트를 잘라냈습니다");
        }
    }

    private void writeHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC);
        header.putInt(schema.columns().size());
        header.putInt(schemaChecksum());
        header.flip();
        channel.write(header, 0);
    }

    private void checkHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        if (channel.read(header, 0) < HEADER_BYTES) {
            throw new StorageException("[open] " + tablePath.getFileName() + " 파일 머리가 손상되었습니다");
        }
        header.flip();
        if (header.getInt() != MAGIC) {
            throw new StorageException("[open] " + tablePath.getFileName() + " 테이블 파일이 아닙니다 (매직 불일치)");
        }
        if (header.getInt() != schema.columns().size() || header.getInt() != schemaChecksum()) {
            throw new StorageException("[open] " + tablePath.getFileName() + " 스키마와 파일이 다릅니다");
        }
    }

    /** 행의 길이만 따라가며 온전한 마지막 행의 끝 위치를 찾는다. */
    private long findValidEnd() throws IOException {
        long fileSize = channel.size();
        long position = HEADER_BYTES;
        try (FileChannel scanChannel = FileChannel.open(tablePath, StandardOpenOption.READ);
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(Channels.newInputStream(scanChannel), SCAN_BUFFER_BYTES))) {
            scanChannel.position(HEADER_BYTES);
            while (position + RowCodec.LENGTH_BYTES <= fileSize) {
                int payloadLength = input.readInt();
                long recordEnd = position + RowCodec.LENGTH_BYTES + (long) payloadLength + RowCodec.CRC_BYTES;
                if (payloadLength < 0 || payloadLength > RowCodec.MAX_PAYLOAD_BYTES || recordEnd > fileSize) {
                    return position;
                }
                input.skipNBytes((long) payloadLength + RowCodec.CRC_BYTES);
                position = recordEnd;
            }
        }
        return position;
    }

    private int schemaChecksum() {
        StringBuilder text = new StringBuilder();
        for (Column column : schema.columns()) {
            text.append(column.columnName()).append(':').append(column.type())
                    .append(':').append(column.maxBytes()).append('|');
        }
        CRC32 crc = new CRC32();
        crc.update(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }
}
