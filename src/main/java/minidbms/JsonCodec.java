package minidbms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CLI·HTTP가 공유하는 작은 JSON 코덱. DB 엔진·파일·네트워크에 의존하지 않는다. */
public final class JsonCodec {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NUMBER_CHARACTERS = 128;
    private final String input;
    private int position;

    private JsonCodec(String input) { this.input = input; }

    /**
     * JSON 한 값을 읽는다. 문자열 이스케이프와 해석 후 중복 필드를 검증한다.
     * @return Map/List/String/Number/Boolean/null. 입력 변경과 자원 사용 없음
     * @throws IllegalArgumentException 잘못된 JSON, 깊이/숫자 길이 제한 초과
     */
    public static Object parse(String input) {
        if (input == null) { throw invalidJson(); }
        JsonCodec parser = new JsonCodec(input);
        Object value = parser.readValue(0);
        parser.skipSpace();
        if (parser.position != input.length()) { throw invalidJson(); }
        return value;
    }

    /**
     * Map/List와 문자열·숫자·불리언·null을 JSON으로 변환한다.
     * @return 새 문자열. 입력 변경과 자원 사용 없음
     * @throws IllegalArgumentException 지원하지 않는 값이나 잘못된 Unicode
     */
    public static String toJson(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value, 0);
        return output.toString();
    }

    private Object readValue(int depth) {
        skipSpace();
        if (depth > MAX_DEPTH || position >= input.length()) { throw invalidJson(); }
        return switch (input.charAt(position)) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", true);
            case 'f' -> readLiteral("false", false);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        position++;
        Map<String, Object> values = new LinkedHashMap<>();
        if (take('}')) { return values; }
        do {
            skipSpace();
            String name = readString();
            if (values.containsKey(name)) { throw invalidJson(); }
            expect(':');
            values.put(name, readValue(depth + 1));
            if (take('}')) { return values; }
        } while (take(','));
        throw invalidJson();
    }

    private List<Object> readArray(int depth) {
        position++;
        List<Object> values = new ArrayList<>();
        if (take(']')) { return values; }
        do {
            values.add(readValue(depth + 1));
            if (take(']')) { return values; }
        } while (take(','));
        throw invalidJson();
    }

    private String readString() {
        if (position >= input.length() || input.charAt(position++) != '"') { throw invalidJson(); }
        StringBuilder value = new StringBuilder();
        while (position < input.length()) {
            char character = input.charAt(position++);
            if (character == '"') {
                String text = value.toString();
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) { throw invalidJson(); }
                return text;
            }
            if (character < 32) { throw invalidJson(); }
            if (character == '\\') { value.append(readEscape()); }
            else { value.append(character); }
        }
        throw invalidJson();
    }

    private char readEscape() {
        if (position >= input.length()) { throw invalidJson(); }
        return switch (input.charAt(position++)) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readHex();
            default -> throw invalidJson();
        };
    }

    private char readHex() {
        if (input.length() - position < 4) { throw invalidJson(); }
        int code = 0;
        for (int index = 0; index < 4; index++) {
            char character = input.charAt(position++);
            int digit = character >= '0' && character <= '9' ? character - '0'
                    : character >= 'a' && character <= 'f' ? character - 'a' + 10
                    : character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
            if (digit < 0) { throw invalidJson(); }
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private Object readLiteral(String literal, Object value) {
        if (!input.startsWith(literal, position)) { throw invalidJson(); }
        position += literal.length();
        return value;
    }

    private Number readNumber() {
        int start = position;
        if (position < input.length() && input.charAt(position) == '-') { position++; }
        if (!isDigit()) { throw invalidJson(); }
        if (input.charAt(position) == '0') { position++; }
        else { readDigits(); }
        if (position < input.length() && input.charAt(position) == '.') {
            position++; readDigits();
        }
        if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
            position++;
            if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                position++;
            }
            readDigits();
        }
        if (position - start > MAX_NUMBER_CHARACTERS) { throw invalidJson(); }
        try { return new BigDecimal(input.substring(start, position)); }
        catch (NumberFormatException failure) { throw invalidJson(); }
    }

    private void readDigits() {
        if (!isDigit()) { throw invalidJson(); }
        while (isDigit()) { position++; }
    }

    private boolean isDigit() {
        return position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9';
    }

    private void skipSpace() {
        while (position < input.length() && " \n\r\t".indexOf(input.charAt(position)) >= 0) { position++; }
    }

    private boolean take(char expected) {
        skipSpace();
        if (position >= input.length() || input.charAt(position) != expected) { return false; }
        position++; return true;
    }

    private void expect(char expected) {
        if (!take(expected)) { throw invalidJson(); }
    }

    private static void writeValue(StringBuilder output, Object value, int depth) {
        if (depth > MAX_DEPTH) { throw invalidJson(); }
        if (value == null) { output.append("null"); }
        else if (value instanceof String text) { writeString(output, text); }
        else if (value instanceof Boolean) { output.append(value); }
        else if (value instanceof Number number) {
            String text = number.toString();
            try { new BigDecimal(text); }
            catch (NumberFormatException failure) { throw invalidJson(); }
            output.append(text);
        } else if (value instanceof Map<?, ?> values) { writeObject(output, values, depth); }
        else if (value instanceof Iterable<?> values) { writeArray(output, values, depth); }
        else { throw invalidJson(); }
    }

    private static void writeObject(StringBuilder output, Map<?, ?> values, int depth) {
        output.append('{');
        boolean isFirst = true;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) { throw invalidJson(); }
            if (!isFirst) { output.append(','); }
            isFirst = false;
            writeString(output, name); output.append(':');
            writeValue(output, entry.getValue(), depth + 1);
        }
        output.append('}');
    }

    private static void writeArray(StringBuilder output, Iterable<?> values, int depth) {
        output.append('[');
        boolean isFirst = true;
        for (Object value : values) {
            if (!isFirst) { output.append(','); }
            isFirst = false;
            writeValue(output, value, depth + 1);
        }
        output.append(']');
    }

    private static void writeString(StringBuilder output, String value) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) { throw invalidJson(); }
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') { output.append('\\').append(character); }
            else if (character < 32) {
                output.append("\\u00");
                output.append(Character.forDigit(character >> 4, 16));
                output.append(Character.forDigit(character & 15, 16));
            } else { output.append(character); }
        }
        output.append('"');
    }

    private static IllegalArgumentException invalidJson() {
        return new IllegalArgumentException("[parseJson] JSON 형식 또는 Unicode를 확인해 주세요");
    }
}
