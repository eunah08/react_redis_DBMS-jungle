package minidbms.storage;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * 테이블별 파일에 행을 추가하고 읽는다. (협약 8.1, 8.2)
 * SQL 문법을 모르고 테이블 이름과 행 단위로만 일한다. (협약 6장)
 * 파일 형식, 스키마 파일 형식, 위치의 의미는 각자 정하고 docs/implementation_notes.md에 쓴다.
 */
public final class TableFile {

    private final Path dataDir;

    /**
     * @param dataDir 스키마 파일과 테이블 파일이 있는 데이터 디렉터리
     */
    public TableFile(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 스키마 파일 data/&lt;테이블이름&gt;.schema 를 읽는다.
     *
     * @param tableName 테이블 이름
     * @return 테이블 정의 (id 컬럼이 맨 앞에 붙은 상태)
     */
    public Schema readSchema(String tableName) {
        // TODO(이니셜): 협약 7.1
        throw new UnsupportedOperationException("[readSchema] 아직 구현되지 않았습니다");
    }

    /**
     * 테이블 파일 끝에 행 하나를 추가한다. 기존 행은 다시 쓰지 않는다.
     *
     * @param tableName 테이블 이름
     * @param row       컬럼 값 목록 (id 포함, 스키마 순서)
     * @return 쓴 행의 위치
     */
    public long appendRow(String tableName, List<Object> row) {
        // TODO(이니셜): 협약 8.2. 쓰다 실패해도 기존 행이 손상되지 않게 한다 (협약 2.1)
        throw new UnsupportedOperationException("[appendRow] 아직 구현되지 않았습니다");
    }

    /**
     * 모든 행을 처음부터 차례로 읽는다. 전체를 한꺼번에 메모리에 올리지 않는다. (권장)
     *
     * @param tableName 테이블 이름
     * @return 행과 위치를 차례로 꺼내는 반복자
     */
    public Iterator<StoredRow> readRows(String tableName) {
        // TODO(이니셜): 협약 8.1
        throw new UnsupportedOperationException("[readRows] 아직 구현되지 않았습니다");
    }

    /**
     * 위치에서 행 하나만 읽는다. 요청마다 버퍼와 읽기 위치를 독립적으로 쓴다. (협약 9.2)
     *
     * @param tableName 테이블 이름
     * @param position  appendRow 또는 readRows가 돌려준 위치
     * @return 컬럼 값 목록
     */
    public List<Object> readRowAt(String tableName, long position) {
        // TODO(이니셜): 협약 9.2
        throw new UnsupportedOperationException("[readRowAt] 아직 구현되지 않았습니다");
    }

    /**
     * 열어 둔 테이블 파일 자원을 닫는다.
     */
    public void close() {
        // TODO(이니셜): 열어 둔 자원이 생기면 여기서 닫는다 (협약 4.3)
    }

    /**
     * @return 데이터 디렉터리
     */
    public Path getDataDir() {
        return dataDir;
    }
}
