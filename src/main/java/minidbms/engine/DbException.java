package minidbms.engine;

/**
 * 엔진 안에서 검증 오류를 위로 올리는 데 쓴다. execute가 잡아서 DbResult 오류로 바꾸므로
 * 엔진 바깥으로는 나가지 않는다. (협약 10.4)
 */
final class DbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * @param errorCode 오류 코드
     * @param message   "[함수이름] 한글 설명" 형식의 메시지
     */
    DbException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @return 오류 코드
     */
    ErrorCode getErrorCode() {
        return errorCode;
    }
}
