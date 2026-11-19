package minidbms.engine;

/**
 * 공통 오류 코드와 HTTP 상태. (협약 10.4)
 * BAD_REQUEST와 SERVER_BUSY는 server가 만든다. 나머지는 engine이 만든다.
 */
public enum ErrorCode {
    BAD_REQUEST(400),
    SQL_SYNTAX(400),
    UNKNOWN_TABLE(400),
    UNKNOWN_COLUMN(400),
    INVALID_VALUE(400),
    SERVER_BUSY(503),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /**
     * @return 이 오류에 대응하는 HTTP 상태 코드
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
