package minidbms.engine;

import java.nio.file.Path;

/**
 * 공통 엔진 진입점. cli와 server는 execute 하나만 부른다. (협약 6장, 8.1)
 * 하는 일 : 파싱 결과 검증, 실행 경로 선택(인덱스 / 선형 탐색), 결과 생성.
 * 출력 형식과 HTTP 응답에 의존하지 않는다. System.exit를 부르지 않는다. (협약 2.1)
 */
public final class DbEngine {

    private final Path dataDir;

    /**
     * 엔진을 연다.
     *
     * @param dataDir 데이터 디렉터리. 프로세스 하나만 사용한다 (협약 8.2)
     */
    public DbEngine(Path dataDir) {
        this.dataDir = dataDir;
        // TODO(이니셜): (Phase 2) 테이블 파일을 읽어 테이블별 인덱스를 다시 만든다 (협약 9.2)
    }

    /**
     * SQL 한 문장을 파싱·검증·실행한다.
     * 입력과 스키마 검증은 파일을 변경하기 전에 마친다. (협약 9.1)
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 결과 객체 (조회 행 / 새 id / 오류 코드)
     */
    public DbResult execute(String sqlText) {
        // TODO(이니셜): Phase 1 흐름은 협약 9.1, 인덱스 연결은 9.2, 잠금은 9.3
        throw new UnsupportedOperationException("[execute] 아직 구현되지 않았습니다");
    }

    /**
     * 테이블 파일 자원을 정리한다.
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
