package minidbms.sql;

/**
 * SQL 문자열을 문장 구조(Statement)로 바꾼다. (협약 7장, 8.1)
 * 순서 : 토큰화 → 구문 검증 → 파싱 결과 생성. 파일·네트워크·인덱스를 모른다. (협약 6장)
 * 토큰 나누기, 문장별 파서 같은 내부 함수와 클래스는 각자 정한다.
 */
public final class SqlParser {

    private SqlParser() {
    }

    /**
     * SQL 문장 하나를 파싱한다.
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 파싱 결과
     */
    public static Statement parseSql(String sqlText) {
        // TODO(이니셜): 지원 문법은 협약 7.2, 구문 오류는 SQL_SYNTAX (협약 10.4)
        throw new UnsupportedOperationException("[parseSql] 아직 구현되지 않았습니다");
    }
}
