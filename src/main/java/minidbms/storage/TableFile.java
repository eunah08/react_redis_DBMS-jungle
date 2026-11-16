package minidbms.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 테이블별 파일에 행을 추가하고 읽는다. (협약 8.1, 8.2)
 * SQL 문법을 모르고 테이블 이름과 행 단위로만 일한다. (협약 6장)
 *
 * <p>파일 : 스키마 {@code <테이블>.schema} (텍스트), 데이터 {@code <테이블>.tbl} (바이너리).
 * 행의 위치는 데이터 파일 안의 바이트 위치(long)이며 행 기록의 시작을 가리킨다.
 * 형식은 {@link SchemaReader}, {@link RowCodec}, {@link TableHandle}에 있다.
 * 테이블마다 파일 채널을 한 번 열어 두고 재사용하므로 {@link #close()}를 불러야 한다.
 * 위치 읽기는 호출마다 버퍼를 새로 만들어 여러 스레드가 함께 불러도 된다.
 * 쓰기(appendRow, rollbackAppend)는 테이블별로 직렬화한다.
 */
public final class TableFile {

    /** 소문자·숫자·밑줄, 1~64자. 길이를 묶어 운영체제 경로 길이 한도에 걸리지 않게 한다. */
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final String SCHEMA_SUFFIX = ".schema";
    private static final String TABLE_SUFFIX = ".tbl";

    private final Path dataDir;
    private final Map<String, TableHandle> handles = new ConcurrentHashMap<>();

    /**
     * @param dataDir 스키마 파일과 테이블 파일이 있는 데이터 디렉터리
     */
    public TableFile(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 테이블 이름이 규칙(소문자·숫자·밑줄, 64자 이하)에 맞는지 알려 준다. 파일 경로에 쓰기 전에 검사한다. (협약 2.1)
     *
     * @param tableName 테이블 이름
     * @return 규칙에 맞으면 true
     */
    public static boolean isValidTableName(String tableName) {
        return tableName != null && TABLE_NAME.matcher(tableName).matches();
    }

    /**
     * 스키마 파일 data/&lt;테이블이름&gt;.schema 를 읽는다. 처음 부르면 테이블 파일도 함께 연다.
     *
     * @param tableName 테이블 이름
     * @return 테이블 정의 (id 컬럼이 맨 앞에 붙은 상태)
     * @throws TableNotFoundException 이름이 규칙에 맞지 않거나 스키마 파일이 없는 경우
     * @throws StorageException       스키마·테이블 파일이 손상된 경우
     */
    public Schema readSchema(String tableName) {
        return getHandle(tableName).schema();
    }

    /**
     * 테이블 파일 끝에 행 하나를 추가한다. 기존 행은 다시 쓰지 않는다.
     * 쓰는 도중 실패하면 쓰던 부분을 잘라내 기존 행이 손상되지 않게 한다.
     *
     * @param tableName 테이블 이름
     * @param row       컬럼 값 목록 (id 포함, 스키마 순서)
     * @return 쓴 행의 위치
     * @throws StorageException 쓰기 실패
     */
    public long appendRow(String tableName, List<Object> row) {
        TableHandle handle = getHandle(tableName);
        byte[] record = RowCodec.encodeRecord(handle.schema(), row);
        synchronized (handle) {
            long startPosition = handle.endPosition();
            try {
                writeFully(handle.channel(), ByteBuffer.wrap(record), startPosition);
            } catch (IOException exception) {
                undoPartialWrite(handle, startPosition);
                throw new StorageException("[appendRow] 행을 쓰지 못했습니다: " + tableName, exception);
            }
            handle.setEndPosition(startPosition + record.length);
            return startPosition;
        }
    }

    /**
     * 방금 추가한 행을 되돌린다. 인덱스 등록에 실패했을 때 파일과 맞추기 위해 engine이 부른다. (협약 9.2)
     *
     * @param tableName 테이블 이름
     * @param position  appendRow가 돌려준 위치. 이 위치부터 끝까지 잘라낸다
     * @throws StorageException 자르지 못한 경우
     */
    public void rollbackAppend(String tableName, long position) {
        TableHandle handle = getHandle(tableName);
        synchronized (handle) {
            if (position < TableHandle.HEADER_BYTES || position > handle.endPosition()) {
                throw new IllegalArgumentException("[rollbackAppend] 위치가 올바르지 않습니다: " + position);
            }
            try {
                handle.channel().truncate(position);
            } catch (IOException exception) {
                throw new StorageException("[rollbackAppend] 행을 되돌리지 못했습니다: " + tableName, exception);
            }
            handle.setEndPosition(position);
        }
    }

    /**
     * 모든 행을 처음부터 차례로 읽는다. 전체를 한꺼번에 메모리에 올리지 않는다.
     * 반복자를 만든 시점의 끝까지만 읽는다. 끝까지 읽지 않고 멈추면 close를 부른다.
     *
     * @param tableName 테이블 이름
     * @return 행과 위치를 차례로 꺼내는 반복자
     */
    public RowCursor readRows(String tableName) {
        TableHandle handle = getHandle(tableName);
        return FileRowCursor.open(handle.schema(), handle.tablePath(), handle.endPosition());
    }

    /**
     * 위치에서 행 하나만 읽는다. 요청마다 버퍼와 읽기 위치를 독립적으로 쓴다. (협약 9.2)
     *
     * @param tableName 테이블 이름
     * @param position  appendRow 또는 readRows가 돌려준 위치
     * @return 컬럼 값 목록
     * @throws StorageException 위치가 행의 시작이 아니거나 행이 손상된 경우
     */
    public List<Object> readRowAt(String tableName, long position) {
        TableHandle handle = getHandle(tableName);
        long endPosition = handle.endPosition();
        if (position < TableHandle.HEADER_BYTES
                || position + RowCodec.LENGTH_BYTES + RowCodec.CRC_BYTES > endPosition) {
            throw new StorageException("[readRowAt] 위치가 파일 범위를 벗어났습니다: " + position);
        }
        try {
            return readRecordAt(handle, position, endPosition);
        } catch (IOException exception) {
            throw new StorageException("[readRowAt] 행을 읽지 못했습니다 (위치 " + position + ")", exception);
        }
    }

    /**
     * 열어 둔 테이블 파일 자원을 모두 닫는다.
     */
    public void close() {
        StorageException failure = null;
        for (TableHandle handle : handles.values()) {
            try {
                handle.close();
            } catch (StorageException exception) {
                failure = exception;
            }
        }
        handles.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * @return 데이터 디렉터리
     */
    public Path getDataDir() {
        return dataDir;
    }

    private List<Object> readRecordAt(TableHandle handle, long position, long endPosition) throws IOException {
        FileChannel channel = handle.channel();
        ByteBuffer lengthBuffer = ByteBuffer.allocate(RowCodec.LENGTH_BYTES);
        readFully(channel, lengthBuffer, position);
        int payloadLength = lengthBuffer.getInt(0);
        long recordEnd = position + RowCodec.LENGTH_BYTES + (long) payloadLength + RowCodec.CRC_BYTES;
        if (payloadLength < 0 || payloadLength > RowCodec.MAX_PAYLOAD_BYTES || recordEnd > endPosition) {
            throw new StorageException("[readRowAt] 행 시작 위치가 아닙니다: " + position);
        }
        ByteBuffer body = ByteBuffer.allocate(payloadLength + RowCodec.CRC_BYTES);
        readFully(channel, body, position + RowCodec.LENGTH_BYTES);
        byte[] payload = new byte[payloadLength];
        body.get(0, payload);
        if (body.getInt(payloadLength) != RowCodec.checksum(payload)) {
            throw new StorageException("[readRowAt] 행이 손상되었습니다 (CRC 불일치, 위치 " + position + ")");
        }
        return RowCodec.decodePayload(handle.schema(), payload);
    }

    private TableHandle getHandle(String tableName) {
        if (!isValidTableName(tableName)) {
            throw new TableNotFoundException("[getHandle] 테이블 이름은 소문자·숫자·밑줄만 쓸 수 있습니다");
        }
        TableHandle handle = handles.get(tableName);
        if (handle != null) {
            return handle;
        }
        return handles.computeIfAbsent(tableName, this::openTable);
    }

    private TableHandle openTable(String tableName) {
        Path schemaPath = dataDir.resolve(tableName + SCHEMA_SUFFIX);
        if (!Files.isRegularFile(schemaPath)) {
            throw new TableNotFoundException("[openTable] 등록되지 않은 테이블입니다: " + tableName);
        }
        Schema schema = SchemaReader.parseSchema(tableName, readSchemaText(schemaPath));
        return TableHandle.open(schema, dataDir.resolve(tableName + TABLE_SUFFIX));
    }

    private static String readSchemaText(Path schemaPath) {
        try {
            byte[] bytes = Files.readAllBytes(schemaPath);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new StorageException("[readSchema] 스키마 파일이 UTF-8이 아닙니다: " + schemaPath.getFileName(), exception);
        } catch (IOException exception) {
            throw new StorageException("[readSchema] 스키마 파일을 읽지 못했습니다: " + schemaPath.getFileName(), exception);
        }
    }

    private static void undoPartialWrite(TableHandle handle, long startPosition) {
        try {
            handle.channel().truncate(startPosition);
        } catch (IOException exception) {
            throw new StorageException("[appendRow] 쓰다 만 행을 잘라내지 못했습니다. 다음 실행 때 복구합니다", exception);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long writePosition = position;
        while (buffer.hasRemaining()) {
            writePosition += channel.write(buffer, writePosition);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long readPosition = position;
        while (buffer.hasRemaining()) {
            int readBytes = channel.read(buffer, readPosition);
            if (readBytes < 0) {
                throw new StorageException("[readRowAt] 행이 중간에 끊겼습니다 (위치 " + position + ")");
            }
            readPosition += readBytes;
        }
    }
}
