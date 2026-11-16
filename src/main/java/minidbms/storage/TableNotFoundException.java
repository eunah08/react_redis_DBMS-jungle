package minidbms.storage;

/**
 * 스키마 파일이 없거나 테이블 이름이 규칙에 맞지 않을 때 던진다.
 * 엔진이 UNKNOWN_TABLE로 바꾼다. (협약 10.4)
 */
public final class TableNotFoundException extends StorageException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message "[함수이름] 한글 설명" 형식의 메시지
     */
    public TableNotFoundException(String message) {
        super(message);
    }
}
