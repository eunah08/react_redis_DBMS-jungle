package minidbms.sql;

/**
 * SQL 구문 오류. engine이 SQL_SYNTAX 오류 코드로 바꾼다. (협약 10.4)
 * 메시지 형식은 "[함수이름] 한글 설명"이다.
 */
public final class SqlSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 오류 설명 ("[함수이름] 한글 설명")
     */
    public SqlSyntaxException(String message) {
        super(message);
    }
}
