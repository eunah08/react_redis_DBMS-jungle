package minidbms.json;

/**
 * 요청 JSON이 올바르지 않을 때 던진다. server가 BAD_REQUEST로 바꾼다. (협약 10.4)
 */
public final class JsonFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message "[함수이름] 한글 설명" 형식의 메시지
     */
    public JsonFormatException(String message) {
        super(message);
    }
}
