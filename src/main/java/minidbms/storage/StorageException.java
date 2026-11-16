package minidbms.storage;

/** 저장 계층 오류. SQL/엔진에 의존하지 않으며 내부 경로는 메시지에 노출하지 않는다. */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean isUnknownTable;

    StorageException(String function, Throwable cause) {
        super("[" + function + "] 저장 파일 또는 스키마를 처리할 수 없습니다", cause);
        isUnknownTable = false;
    }

    private StorageException() {
        super("[readSchema] 등록되지 않은 테이블입니다");
        isUnknownTable = true;
    }

    static StorageException unknownTable() { return new StorageException(); }

    /** @return 스키마 파일이 없거나 허용되지 않은 테이블 이름이면 true */
    public boolean isUnknownTable() { return isUnknownTable; }
}
