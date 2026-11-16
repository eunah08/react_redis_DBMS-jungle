package minidbms.json;

import minidbms.engine.DbResult;
import minidbms.sql.StatementType;

import java.util.List;

/**
 * 결과 객체를 공통 JSON 한 줄로 바꾼다. (협약 10.2, 10.4)
 * 외부 라이브러리 없이 직접 만든다. 문자열 값은 항상 이스케이프한다. (협약 10.3)
 * cli와 server가 함께 쓴다. 엔진은 이 클래스를 모른다. (협약 6장)
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * @param result 엔진 결과
     * @return INSERT는 {"id":1}, SELECT는 {"rows":[...]}, 오류는 {"error":"SQL_SYNTAX"}
     */
    public static String toJson(DbResult result) {
        if (result.isError()) {
            return toErrorJson(result.getErrorCode().name());
        }
        if (result.getType() == StatementType.INSERT) {
            return "{\"id\":" + result.getInsertedId() + "}";
        }
        return toRowsJson(result.getColumnNames(), result.getRows());
    }

    /**
     * @param errorName 오류 이름
     * @return {"error":"이름"}
     */
    public static String toErrorJson(String errorName) {
        return "{\"error\":" + quote(errorName) + "}";
    }

    /**
     * @param text 원래 문자열
     * @return 따옴표로 감싸고 이스케이프한 JSON 문자열
     */
    public static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2);
        quoted.append('"');
        for (int i = 0; i < text.length(); i++) {
            appendEscaped(quoted, text.charAt(i));
        }
        return quoted.append('"').toString();
    }

    private static String toRowsJson(List<String> columnNames, List<List<Object>> rows) {
        StringBuilder json = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendRow(json, columnNames, rows.get(i));
        }
        return json.append("]}").toString();
    }

    private static void appendRow(StringBuilder json, List<String> columnNames, List<Object> row) {
        json.append('{');
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(columnNames.get(i))).append(':');
            Object value = row.get(i);
            json.append(value instanceof String text ? quote(text) : String.valueOf(value));
        }
        json.append('}');
    }

    private static void appendEscaped(StringBuilder quoted, char character) {
        switch (character) {
            case '"' -> quoted.append("\\\"");
            case '\\' -> quoted.append("\\\\");
            case '\n' -> quoted.append("\\n");
            case '\r' -> quoted.append("\\r");
            case '\t' -> quoted.append("\\t");
            case '\b' -> quoted.append("\\b");
            case '\f' -> quoted.append("\\f");
            default -> appendPlainOrUnicode(quoted, character);
        }
    }

    private static void appendPlainOrUnicode(StringBuilder quoted, char character) {
        if (character < 0x20) {
            quoted.append(String.format("\\u%04x", (int) character));
        } else {
            quoted.append(character);
        }
    }
}
