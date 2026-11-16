package minidbms.server;

import minidbms.engine.DbResult;

/**
 * 서버가 SQL을 실행하는 길. 운영에서는 DbEngine::execute이고, 테스트에서는 일부러 느리게 하거나
 * 막히게 하는 구현을 끼워 큐 포화를 재현한다. 서버는 이것으로 DbEngine.execute만 부른다. (협약 6장)
 */
@FunctionalInterface
public interface SqlExecutor {

    /**
     * SQL 한 문장을 실행한다.
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 결과 객체
     */
    DbResult execute(String sqlText);
}
