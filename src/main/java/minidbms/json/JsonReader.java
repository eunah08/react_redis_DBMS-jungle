package minidbms.json;

import java.util.HashSet;
import java.util.Set;

/**
 * POST /sql 요청 본문 {"sql":"..."} 을 읽는다. (협약 10.3)
 * JSON 문법을 끝까지 검사하고, 문자열 필드 sql 하나만 있는 객체일 때만 값을 돌려준다.
 * \", \\, \/, \b, \f, \n, \r, \t, \\uXXXX 이스케이프를 처리한다. 외부 라이브러리를 쓰지 않는다.
 */
public final class JsonReader {

    private static final int MAX_DEPTH = 32;
    private static final String SQL_FIELD = "sql";

    private final String text;
    private int index;

    private JsonReader(String text) {
        this.text = text;
    }

    /**
     * @param body 요청 본문 (UTF-8로 읽은 글)
     * @return sql 필드의 값
     * @throws JsonFormatException 잘못된 JSON, sql 누락·중복, 문자열이 아닌 sql, 추가 필드가 있을 때
     */
    public static String readSqlRequest(String body) {
        return new JsonReader(body).readRequest();
    }

    private String readRequest() {
        skipSpaces();
        expect('{', "[readRequest] JSON 객체가 아닙니다");
        String sql = null;
        int memberCount = 0;
        Set<String> keys = new HashSet<>();
        skipSpaces();
        if (peek() == '}') {
            throw new JsonFormatException("[readRequest] sql 필드가 없습니다");
        }
        do {
            skipSpaces();
            String key = readString();
            if (!keys.add(key)) {
                throw new JsonFormatException("[readRequest] 같은 필드가 두 번 있습니다: " + key);
            }
            skipSpaces();
            expect(':', "[readRequest] 필드 이름 뒤에 ':'가 필요합니다");
            skipSpaces();
            Object value = readValue(1);
            sql = SQL_FIELD.equals(key) && value instanceof String ? (String) value : sql;
            memberCount++;
            skipSpaces();
        } while (consumeIf(','));
        expect('}', "[readRequest] 객체가 '}'로 끝나야 합니다");
        skipSpaces();
        return checkRequest(sql, memberCount);
    }

    private String checkRequest(String sql, int memberCount) {
        if (index != text.length()) {
            throw new JsonFormatException("[checkRequest] JSON 뒤에 다른 내용이 있습니다");
        }
        if (memberCount != 1 || sql == null) {
            throw new JsonFormatException("[checkRequest] 문자열 필드 sql 하나만 있어야 합니다");
        }
        return sql;
    }

    /** 값 하나를 읽는다. 문자열이면 그 값을, 그 밖의 값은 문법만 확인하고 빈 객체를 돌려준다. */
    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonFormatException("[readValue] JSON이 너무 깊게 중첩되었습니다");
        }
        char current = peek();
        if (current == '"') {
            return readString();
        }
        if (current == '{') {
            skipObject(depth);
        } else if (current == '[') {
            skipArray(depth);
        } else if (current == '-' || (current >= '0' && current <= '9')) {
            skipNumber();
        } else {
            skipLiteral();
        }
        return Boolean.TRUE;
    }

    private void skipObject(int depth) {
        expect('{', "[skipObject] '{'가 필요합니다");
        skipSpaces();
        if (consumeIf('}')) {
            return;
        }
        do {
            skipSpaces();
            readString();
            skipSpaces();
            expect(':', "[skipObject] 필드 이름 뒤에 ':'가 필요합니다");
            skipSpaces();
            readValue(depth + 1);
            skipSpaces();
        } while (consumeIf(','));
        expect('}', "[skipObject] 객체가 '}'로 끝나야 합니다");
    }

    private void skipArray(int depth) {
        expect('[', "[skipArray] '['가 필요합니다");
        skipSpaces();
        if (consumeIf(']')) {
            return;
        }
        do {
            skipSpaces();
            readValue(depth + 1);
            skipSpaces();
        } while (consumeIf(','));
        expect(']', "[skipArray] 배열이 ']'로 끝나야 합니다");
    }

    private void skipNumber() {
        consumeIf('-');
        if (consumeIf('0')) {
            // 0 앞에는 다른 숫자가 올 수 없으므로 정수 부분이 끝났다
            skipFractionAndExponent();
            return;
        }
        if (skipDigits() == 0) {
            throw new JsonFormatException("[skipNumber] 숫자가 올바르지 않습니다");
        }
        skipFractionAndExponent();
    }

    private void skipFractionAndExponent() {
        if (consumeIf('.') && skipDigits() == 0) {
            throw new JsonFormatException("[skipNumber] 소수점 뒤에 숫자가 필요합니다");
        }
        if (consumeIf('e') || consumeIf('E')) {
            if (!consumeIf('+')) {
                consumeIf('-');
            }
            if (skipDigits() == 0) {
                throw new JsonFormatException("[skipNumber] 지수 뒤에 숫자가 필요합니다");
            }
        }
    }

    private int skipDigits() {
        int count = 0;
        while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
            index++;
            count++;
        }
        return count;
    }

    private void skipLiteral() {
        for (String literal : new String[] {"true", "false", "null"}) {
            if (text.startsWith(literal, index)) {
                index += literal.length();
                return;
            }
        }
        throw new JsonFormatException("[skipLiteral] 올바르지 않은 JSON 값입니다 (" + (index + 1) + "번째 글자)");
    }

    private String readString() {
        expect('"', "[readString] 문자열은 큰따옴표로 시작해야 합니다");
        StringBuilder value = new StringBuilder();
        while (true) {
            if (index >= text.length()) {
                throw new JsonFormatException("[readString] 문자열이 닫히지 않았습니다");
            }
            char current = text.charAt(index++);
            if (current == '"') {
                return value.toString();
            }
            if (current < 0x20) {
                throw new JsonFormatException("[readString] 문자열 안에 줄바꿈 같은 제어 문자가 있습니다");
            }
            value.append(current == '\\' ? readEscape() : current);
        }
    }

    private char readEscape() {
        if (index >= text.length()) {
            throw new JsonFormatException("[readEscape] 이스케이프가 끝났습니다");
        }
        char code = text.charAt(index++);
        return switch (code) {
            case '"', '\\', '/' -> code;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw new JsonFormatException("[readEscape] 지원하지 않는 이스케이프입니다: \\" + code);
        };
    }

    private char readUnicodeEscape() {
        if (index + 4 > text.length()) {
            throw new JsonFormatException("[readUnicodeEscape] \\u 뒤에 16진수 4자리가 필요합니다");
        }
        try {
            char character = (char) Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return character;
        } catch (NumberFormatException e) {
            throw new JsonFormatException("[readUnicodeEscape] \\u 뒤에 16진수 4자리가 필요합니다");
        }
    }

    private void skipSpaces() {
        while (index < text.length() && isJsonSpace(text.charAt(index))) {
            index++;
        }
    }

    private static boolean isJsonSpace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

    private char peek() {
        if (index >= text.length()) {
            throw new JsonFormatException("[peek] JSON이 도중에 끝났습니다");
        }
        return text.charAt(index);
    }

    private boolean consumeIf(char expected) {
        if (index < text.length() && text.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected, String message) {
        if (!consumeIf(expected)) {
            throw new JsonFormatException(message + " (" + (index + 1) + "번째 글자)");
        }
    }
}
