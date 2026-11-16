package minidbms.json;

/**
 * 잘못된 JSON. server가 BAD_REQUEST로 바꾼다. (협약 10.4)
 */
public final class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 오류 설명 ("[함수이름] 한글 설명")
     */
    public JsonParseException(String message) {
        super(message);
    }
}
