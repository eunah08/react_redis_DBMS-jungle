package minidbms.engine;

import minidbms.sql.Statement;

/** 협약 12장의 빠른 반복 적재용 진입점. 검증·잠금·파일·인덱스 경로는 그대로 사용한다. */
public final class BulkLoader {
    private BulkLoader() { }

    /**
     * 파싱된 문장을 실행한다. CLI/HTTP는 이 경로를 사용하지 않는다.
     * @param engine 자원 소유권을 유지할 엔진
     * @param statement 파싱을 생략한 문장
     * @return 일반 execute와 같은 결과. 엔진을 닫지 않는다.
     */
    public static DbResult execute(DbEngine engine, Statement statement) {
        return engine.executeStatement(statement);
    }
}
