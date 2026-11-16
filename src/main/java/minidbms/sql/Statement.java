package minidbms.sql;

import java.util.List;

/**
 * SQL 문장 하나의 파싱 결과. 세 구현이 같은 모양을 쓴다. (협약 7.3)
 * 파서는 스키마를 모르므로 값은 SQL에 적힌 모양 그대로 담는다. 자료형 검사는 engine이 한다.
 *
 * @param type      문장 종류
 * @param tableName 테이블 이름
 * @param columns   컬럼 목록. SELECT * 이면 ["*"]
 * @param values    INSERT만 사용. columns와 순서·개수가 같다
 * @param condition SELECT만 사용. WHERE가 없으면 null
 */
public record Statement(
        StatementType type,
        String tableName,
        List<String> columns,
        List<Object> values,
        Condition condition) {

    /** SELECT * 를 뜻하는 컬럼 표시. */
    public static final String ALL_COLUMNS = "*";

    /**
     * WHERE 조건이 있는지 알려 준다.
     *
     * @return 조건이 있으면 true
     */
    public boolean hasWhere() {
        return condition != null;
    }
}
