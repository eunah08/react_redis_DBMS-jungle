package minidbms.engine;

/**
 * 엔진 안에서 오류 코드와 함께 실행을 멈추는 예외. DbEngine.execute가 잡아 DbResult로 바꾼다.
 * 엔진 바깥으로 나가지 않는다.
 */
final class EngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    EngineException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    ErrorCode getErrorCode() {
        return errorCode;
    }
}
