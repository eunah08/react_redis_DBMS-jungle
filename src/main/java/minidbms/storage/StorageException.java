package minidbms.storage;

/**
 * 파일 읽기·쓰기 실패, 손상된 파일, 잘못된 스키마 파일. engine이 INTERNAL_ERROR로 바꾼다. (협약 10.4)
 * 메시지 형식은 "[함수이름] 한글 설명"이다.
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 오류 설명
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * @param message 오류 설명
     * @param cause   원인
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
