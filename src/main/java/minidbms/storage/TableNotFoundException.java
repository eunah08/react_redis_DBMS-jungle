package minidbms.storage;

/**
 * 스키마 파일이 없거나 테이블 이름이 규칙에 맞지 않는 경우. engine이 UNKNOWN_TABLE로 바꾼다. (협약 10.4)
 */
public final class TableNotFoundException extends StorageException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 오류 설명
     */
    public TableNotFoundException(String message) {
        super(message);
    }
}
