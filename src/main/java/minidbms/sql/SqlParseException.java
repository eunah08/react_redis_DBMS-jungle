package minidbms.sql;

/** 파싱 오류. 엔진 의존 없이 구문 오류와 정수 범위 오류를 구분한다. */
public final class SqlParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final boolean isInvalidValue;

    SqlParseException(String message, boolean isInvalidValue) {
        super(message);
        this.isInvalidValue = isInvalidValue;
    }

    /** @return 숫자 범위 오류이면 true, 구문 오류이면 false */
    public boolean isInvalidValue() {
        return isInvalidValue;
    }

    static SqlParseException syntax(int position) {
        return new SqlParseException("[parseSql] " + (position + 1)
                + "번째 위치의 SQL 문법을 확인해 주세요", false);
    }
}
