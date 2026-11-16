package minidbms.engine;

/** 엔진 내부 검증 실패. execute 경계에서 DbResult로 변환한다. */
final class DbFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    DbFailure(ErrorCode code, String function, String description) {
        super("[" + function + "] " + description);
        this.code = code;
    }

    ErrorCode code() { return code; }
}
