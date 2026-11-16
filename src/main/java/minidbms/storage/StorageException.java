package minidbms.storage;

/**
 * 스키마·테이블 파일이 손상되었거나 값이 스키마와 맞지 않을 때 던진다.
 * 엔진이 INTERNAL_ERROR로 바꾼다. (협약 10.4)
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message "[함수이름] 한글 설명" 형식의 메시지
     */
    public StorageException(String message) {
        super(message);
    }
}
