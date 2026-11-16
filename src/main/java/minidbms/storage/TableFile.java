package minidbms.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 테이블별 파일에 행을 추가하고 읽는다. (협약 8.1, 8.2)
 * SQL 문법을 모르고 테이블 이름과 행 단위로만 일한다. (협약 6장)
 * 파일 형식은 RowCodec 설명을, 고른 이유는 docs/implementation_notes.md를 본다.
 * 행의 위치는 파일 안의 바이트 위치(long)다.
 * 같은 테이블의 쓰기는 한 번에 하나씩이어야 하며, 그 잠금은 engine이 잡는다. (협약 9.3)
 * 읽기는 여러 스레드가 함께 해도 된다.
 */
public final class TableFile {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");
    private static final String SCHEMA_SUFFIX = ".schema";
    private static final String TABLE_SUFFIX = ".tbl";
    private static final int SCAN_CHUNK_BYTES = 1 << 20;
    private static final int POINT_CHUNK_BYTES = 4096;

    private final Path dataDir;
    private final Map<String, OpenTable> openTables = new ConcurrentHashMap<>();

    /** 열어 둔 테이블 파일 하나. endPosition은 온전한 행들이 끝나는 위치다. */
    private static final class OpenTable {
        private final Schema schema;
        private final FileChannel channel;
        private volatile long endPosition;
        private boolean isDamaged;

        private OpenTable(Schema schema, FileChannel channel, long endPosition) {
            this.schema = schema;
            this.channel = channel;
            this.endPosition = endPosition;
        }
    }

    /**
     * @param dataDir 스키마 파일과 테이블 파일이 있는 데이터 디렉터리
     */
    public TableFile(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 스키마 파일 data/&lt;테이블이름&gt;.schema 를 읽는다.
     *
     * @param tableName 테이블 이름 (소문자·숫자·밑줄)
     * @return 테이블 정의 (id 컬럼이 맨 앞에 붙은 상태)
     * @throws TableNotFoundException 이름이 규칙에 맞지 않거나 스키마 파일이 없을 때
     * @throws StorageException       스키마 파일 형식이 올바르지 않을 때
     */
    public Schema readSchema(String tableName) {
        checkTableName(tableName);
        Path schemaPath = dataDir.resolve(tableName + SCHEMA_SUFFIX);
        if (!Files.isRegularFile(schemaPath)) {
            throw new TableNotFoundException("[readSchema] 스키마 파일이 없는 테이블입니다: " + tableName);
        }
        try {
            return SchemaFile.parse(tableName, Files.readAllLines(schemaPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("[readSchema] 스키마 파일을 읽지 못했습니다", e);
        }
    }

    /**
     * 테이블 파일 끝에 행 하나를 추가한다. 기존 행은 다시 쓰지 않는다.
     * 행 하나를 한 번에 쓰고, 쓰다 실패하면 파일을 쓰기 전 크기로 되돌린다.
     *
     * @param tableName 테이블 이름
     * @param row       컬럼 값 목록 (id 포함, 스키마 순서)
     * @return 쓴 행의 위치
     */
    public long appendRow(String tableName, List<Object> row) {
        OpenTable table = openTable(tableName);
        byte[] record = RowCodec.encodeRow(table.schema, row);
        synchronized (table) {
            if (table.isDamaged) {
                throw new StorageException("[appendRow] 이전 쓰기 실패를 복구하지 못한 파일입니다: " + tableName);
            }
            long position = table.endPosition;
            writeRecord(table, record, position);
            table.endPosition = position + record.length;
            return position;
        }
    }

    /**
     * 방금 추가한 행을 지우고 파일을 그 위치로 되돌린다. INSERT가 중간에 실패했을 때 쓴다. (협약 9.2)
     *
     * @param tableName 테이블 이름
     * @param position  appendRow가 돌려준 위치. 파일의 마지막 행이어야 한다
     */
    public void rollbackAppend(String tableName, long position) {
        OpenTable table = openTable(tableName);
        synchronized (table) {
            if (position < RowCodec.HEADER_BYTES || position > table.endPosition) {
                throw new StorageException("[rollbackAppend] 되돌릴 수 없는 위치입니다: " + position);
            }
            try {
                table.channel.truncate(position);
            } catch (IOException e) {
                table.isDamaged = true;
                throw new UncheckedIOException("[rollbackAppend] 파일을 되돌리지 못했습니다", e);
            }
            table.endPosition = position;
        }
    }

    /**
     * 모든 행을 처음부터 차례로 읽는다. 한 번에 한 덩어리씩만 메모리에 올린다.
     * 호출한 시점에 온전히 쓰인 행까지만 나온다.
     *
     * @param tableName 테이블 이름
     * @return 행과 위치를 차례로 꺼내는 반복자 (닫을 자원 없음)
     */
    public Iterator<StoredRow> readRows(String tableName) {
        OpenTable table = openTable(tableName);
        long endPosition = table.endPosition;
        RowReader reader = new RowReader(table.channel, endPosition, SCAN_CHUNK_BYTES);
        return new RowIterator(table.schema, reader, endPosition);
    }

    /**
     * 위치에서 행 하나만 읽는다. 호출마다 버퍼와 읽기 위치를 새로 만든다. (협약 9.2)
     *
     * @param tableName 테이블 이름
     * @param position  appendRow 또는 readRows가 돌려준 위치
     * @return 컬럼 값 목록
     * @throws StorageException 위치가 행의 시작이 아닐 때
     */
    public List<Object> readRowAt(String tableName, long position) {
        OpenTable table = openTable(tableName);
        long endPosition = table.endPosition;
        if (position < RowCodec.HEADER_BYTES || position >= endPosition) {
            throw new StorageException("[readRowAt] 범위를 벗어난 위치입니다: " + position);
        }
        RowReader reader = new RowReader(table.channel, endPosition, POINT_CHUNK_BYTES);
        return reader.readRow(table.schema, position).row();
    }

    /**
     * 열어 둔 테이블 파일 자원을 모두 닫는다. 이 객체는 닫은 뒤에도 다시 쓸 수 있다(파일을 새로 연다).
     */
    public void close() {
        IOException firstError = null;
        for (OpenTable table : openTables.values()) {
            try {
                table.channel.close();
            } catch (IOException e) {
                firstError = firstError == null ? e : firstError;
            }
        }
        openTables.clear();
        if (firstError != null) {
            throw new UncheckedIOException("[close] 테이블 파일을 닫지 못했습니다", firstError);
        }
    }

    /**
     * @return 데이터 디렉터리
     */
    public Path getDataDir() {
        return dataDir;
    }

    private static void checkTableName(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new TableNotFoundException("[checkTableName] 테이블 이름은 소문자·숫자·밑줄만 쓸 수 있습니다");
        }
    }

    private OpenTable openTable(String tableName) {
        return openTables.computeIfAbsent(tableName, this::createOpenTable);
    }

    private OpenTable createOpenTable(String tableName) {
        Schema schema = readSchema(tableName);
        Path tablePath = dataDir.resolve(tableName + TABLE_SUFFIX);
        try {
            FileChannel channel = FileChannel.open(tablePath,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            return prepareTable(schema, channel);
        } catch (IOException e) {
            throw new UncheckedIOException("[createOpenTable] 테이블 파일을 열지 못했습니다", e);
        }
    }

    private OpenTable prepareTable(Schema schema, FileChannel channel) throws IOException {
        try {
            return new OpenTable(schema, channel, prepareFile(schema, channel));
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** 새 파일이면 헤더를 쓰고, 기존 파일이면 헤더를 확인하고 덜 쓰인 끝부분을 잘라낸다. */
    private long prepareFile(Schema schema, FileChannel channel) throws IOException {
        long size = channel.size();
        if (size == 0) {
            writeFully(channel, ByteBuffer.wrap(buildHeader(schema)), 0);
            return RowCodec.HEADER_BYTES;
        }
        checkHeader(schema, channel, size);
        long validEnd = new RowReader(channel, size, SCAN_CHUNK_BYTES).findValidEnd();
        if (validEnd < size) {
            channel.truncate(validEnd);
        }
        return validEnd;
    }

    private static byte[] buildHeader(Schema schema) {
        ByteBuffer header = ByteBuffer.allocate(RowCodec.HEADER_BYTES);
        header.put(RowCodec.MAGIC);
        header.putInt(RowCodec.schemaChecksum(schema));
        return header.array();
    }

    private void checkHeader(Schema schema, FileChannel channel, long size) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(RowCodec.HEADER_BYTES);
        if (size >= RowCodec.HEADER_BYTES) {
            channel.read(header, 0);
        }
        if (size < RowCodec.HEADER_BYTES
                || !java.util.Arrays.equals(java.util.Arrays.copyOf(header.array(), RowCodec.MAGIC_BYTES), RowCodec.MAGIC)) {
            throw new StorageException("[checkHeader] 테이블 파일 헤더가 손상되었습니다");
        }
        if (header.getInt(RowCodec.MAGIC_BYTES) != RowCodec.schemaChecksum(schema)) {
            throw new StorageException("[checkHeader] 스키마와 테이블 파일이 다릅니다 (스키마를 바꿨다면 data의 .tbl을 지워야 합니다)");
        }
    }

    private void writeRecord(OpenTable table, byte[] record, long position) {
        try {
            writeFully(table.channel, ByteBuffer.wrap(record), position);
        } catch (IOException e) {
            restoreAfterFailedWrite(table, position, e);
            throw new UncheckedIOException("[appendRow] 행을 쓰지 못했습니다", e);
        }
    }

    private void restoreAfterFailedWrite(OpenTable table, long position, IOException cause) {
        try {
            table.channel.truncate(position);
        } catch (IOException truncateError) {
            table.isDamaged = true;
            cause.addSuppressed(truncateError);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long current = position;
        while (buffer.hasRemaining()) {
            current += channel.write(buffer, current);
        }
    }
}
