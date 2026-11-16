package minidbms.sql;

/**
 * SQL 구문 오류. 엔진이 SQL_SYNTAX 오류 코드로 바꾼다. (협약 10.4)
 * 메시지 형식은 "[함수이름] 한글 설명 (위치)"이다.
 */
public final class SqlSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 원인과 위치를 담은 설명
     */
    public SqlSyntaxException(String message) {
        super(message);
    }
}
