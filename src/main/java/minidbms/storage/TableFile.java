package minidbms.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 테이블별 파일과 채널을 소유한다. SQL과 인덱스를 모른다. close는 호출자 책임이다. */
public final class TableFile implements AutoCloseable {
    private final Path dataDir;
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private final Map<String, RowFile> files = new ConcurrentHashMap<>();
    private volatile boolean isClosed;

    /** @param dataDir 스키마와 테이블 파일 디렉터리. 생성 실패는 StorageException */
    public TableFile(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        try { Files.createDirectories(this.dataDir); }
        catch (IOException failure) { throw new StorageException("TableFile", failure); }
    }

    /**
     * 스키마를 읽고 id를 맨 앞에 붙인다. 불변 결과를 캐시한다.
     * @param tableName 소문자·숫자·밑줄 이름
     * @return 스키마. 부재/손상 시 StorageException. 파일 내용은 변경하지 않는다.
     */
    public Schema readSchema(String tableName) {
        checkOpen();
        return schemas.computeIfAbsent(tableName, name -> SchemaReader.read(dataDir, name));
    }

    /** @return 등록된 스키마의 테이블 이름. 채널을 열지 않는다. 실패 시 StorageException */
    public List<String> tableNames() {
        checkOpen();
        try (var paths = Files.list(dataDir)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".schema"))
                    .map(name -> name.substring(0, name.length() - ".schema".length()))
                    .filter(SchemaReader::isTableName).sorted().toList();
        } catch (IOException failure) { throw new StorageException("tableNames", failure); }
    }

    /**
     * 행 하나를 끝에 추가한다. 실패한 부분 쓰기는 원래 끝으로 되돌린다.
     * @return long 바이트 위치. 파일/자료형 오류는 StorageException
     */
    public long appendRow(String tableName, List<Object> row) {
        return getFile(tableName).appendRow(row);
    }

    /** @return 요청별 버퍼와 위치를 가진 반복자. 파일 자원은 TableFile이 소유한다. */
    public Iterator<StoredRow> readRows(String tableName) {
        return getFile(tableName).readRows();
    }

    /** @return 지정 위치의 불변 행. 위치/파일 손상은 StorageException */
    public List<Object> readRowAt(String tableName, long position) {
        return getFile(tableName).readRowAt(position);
    }

    /** 엔진 쓰기 락 안에서 마지막 INSERT를 취소한다. 실패하면 해당 파일을 차단한다. */
    public void rollbackAppend(String tableName, long position) {
        getFile(tableName).rollbackAppend(position);
    }

    private RowFile getFile(String tableName) {
        checkOpen();
        Schema schema = readSchema(tableName);
        return files.computeIfAbsent(tableName,
                name -> RowFile.open(dataDir.resolve(name + ".table"), schema));
    }

    private void checkOpen() {
        if (isClosed) { throw new StorageException("TableFile", new IOException("닫힌 저장소")); }
    }

    /** 모든 채널을 닫는다. 실패해도 다른 채널의 종료를 계속하고 마지막에 오류를 알린다. */
    @Override public void close() {
        if (isClosed) { return; }
        isClosed = true;
        StorageException failure = null;
        for (RowFile file : files.values()) {
            try { file.close(); }
            catch (StorageException closeFailure) {
                if (failure == null) { failure = closeFailure; }
                else { failure.addSuppressed(closeFailure); }
            }
        }
        files.clear();
        if (failure != null) { throw failure; }
    }

    /** @return 정규화한 데이터 디렉터리. 상태 변경 없음 */
    public Path getDataDir() { return dataDir; }
}
