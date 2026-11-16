package minidbms.json;

import java.util.List;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;

/**
 * 결과 객체를 공통 JSON 문자열로 바꾼다. (협약 10.2, 10.3, 10.4)
 * 출력 형식은 cli와 server가 맡으므로 engine이 아니라 여기에 둔다. 문자열 값은 반드시 이스케이프한다.
 * JSON 라이브러리를 쓰지 않는다. (협약 2장 : 허용이지만 필요하지 않아 쓰지 않았다)
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * 성공 결과를 JSON으로 바꾼다. 오류 결과이면 {"error":"코드"}로 바꾼다.
     * 접근 방식(INDEX / FULL_SCAN)은 공통 출력에 넣지 않는다. (협약 8.1)
     *
     * @param result 엔진 결과
     * @return 한 줄 JSON
     */
    public static String toJson(DbResult result) {
        return switch (result.getKind()) {
            case INSERT -> "{\"id\":" + result.getInsertedId() + "}";
            case SELECT -> selectToJson(result.getColumnNames(), result.getRows());
            case ERROR -> errorToJson(result.getErrorCode().name());
        };
    }

    /**
     * 오류 코드를 JSON으로 바꾼다.
     *
     * @param errorCode 오류 코드 이름 (예 : SQL_SYNTAX)
     * @return {"error":"코드"}
     */
    public static String errorToJson(String errorCode) {
        return "{\"error\":" + quote(errorCode) + "}";
    }

    /**
     * 오류 코드를 JSON으로 바꾼다.
     *
     * @param errorCode 오류 코드
     * @return {"error":"코드"}
     */
    public static String errorToJson(ErrorCode errorCode) {
        return errorToJson(errorCode.name());
    }

    /**
     * 문자열을 따옴표로 감싼 JSON 문자열로 바꾼다. 따옴표·역슬래시·제어 문자를 이스케이프한다.
     *
     * @param text 원문
     * @return JSON 문자열
     */
    public static String quote(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 2);
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            appendEscaped(builder, text.charAt(i));
        }
        return builder.append('"').toString();
    }

    private static String selectToJson(List<String> columnNames, List<List<Object>> rows) {
        StringBuilder builder = new StringBuilder("{\"rows\":[");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                builder.append(',');
            }
            appendRow(builder, columnNames, rows.get(rowIndex));
        }
        return builder.append("]}").toString();
    }

    private static void appendRow(StringBuilder builder, List<String> columnNames, List<Object> row) {
        builder.append('{');
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(quote(columnNames.get(i))).append(':');
            Object value = row.get(i);
            builder.append(value instanceof String text ? quote(text) : String.valueOf(value));
        }
        builder.append('}');
    }

    private static void appendEscaped(StringBuilder builder, char letter) {
        switch (letter) {
            case '"' -> builder.append("\\\"");
            case '\\' -> builder.append("\\\\");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            case '\b' -> builder.append("\\b");
            case '\f' -> builder.append("\\f");
            default -> {
                if (letter < 0x20) {
                    builder.append(String.format("\\u%04x", (int) letter));
                } else {
                    builder.append(letter);
                }
            }
        }
    }
}
