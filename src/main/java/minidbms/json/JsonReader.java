package minidbms.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청 본문용 엄격한 JSON 읽기. (협약 10.3 : \", \\, \n 이스케이프 처리)
 * 값은 Map(객체, 키가 겹치면 오류), List(배열), String, BigDecimal(숫자), Boolean, null로 돌려준다.
 * 값 하나 뒤에 다른 글자가 있으면 오류이고, 중첩은 {@link #MAX_DEPTH}단계까지만 허용한다.
 */
public final class JsonReader {

    /** 중첩 깊이 한도. 깊게 겹친 입력으로 스택이 넘치는 것을 막는다. */
    public static final int MAX_DEPTH = 32;

    private final String text;
    private int index;

    private JsonReader(String text) {
        this.text = text;
    }

    /**
     * JSON 값 하나를 읽는다.
     *
     * @param text JSON 원문
     * @return 읽은 값
     * @throws JsonParseException 문법 오류, 겹친 키, 너무 깊은 중첩, 뒤에 남은 글자
     */
    public static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipBlank();
        Object value = reader.readValue(1);
        reader.skipBlank();
        if (reader.index < text.length()) {
            throw reader.error("값 뒤에 다른 글자가 있습니다");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("중첩이 너무 깊습니다");
        }
        if (index >= text.length()) {
            throw error("값이 필요합니다");
        }
        char letter = text.charAt(index);
        return switch (letter) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        index++;
        skipBlank();
        if (consumeIf('}')) {
            return members;
        }
        while (true) {
            skipBlank();
            if (index >= text.length() || text.charAt(index) != '"') {
                throw error("객체의 키는 문자열이어야 합니다");
            }
            String key = readString();
            skipBlank();
            expect(':');
            skipBlank();
            if (members.containsKey(key)) {
                throw error("같은 키가 두 번 나왔습니다: " + key);
            }
            members.put(key, readValue(depth + 1));
            skipBlank();
            if (consumeIf('}')) {
                return members;
            }
            expect(',');
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> elements = new ArrayList<>();
        index++;
        skipBlank();
        if (consumeIf(']')) {
            return elements;
        }
        while (true) {
            skipBlank();
            elements.add(readValue(depth + 1));
            skipBlank();
            if (consumeIf(']')) {
                return elements;
            }
            expect(',');
        }
    }

    private String readString() {
        StringBuilder value = new StringBuilder();
        index++;
        while (index < text.length()) {
            char letter = text.charAt(index++);
            if (letter == '"') {
                return value.toString();
            }
            if (letter < 0x20) {
                throw error("문자열 안에 이스케이프하지 않은 제어 문자가 있습니다");
            }
            if (letter == '\\') {
                value.append(readEscape());
            } else {
                value.append(letter);
            }
        }
        throw error("닫히지 않은 문자열입니다");
    }

    private char readEscape() {
        if (index >= text.length()) {
            throw error("이스케이프가 끝나지 않았습니다");
        }
        char letter = text.charAt(index++);
        return switch (letter) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("알 수 없는 이스케이프입니다: \\" + letter);
        };
    }

    private char readUnicodeEscape() {
        if (index + 4 > text.length()) {
            throw error("\\u 뒤에 16진수 4자리가 필요합니다");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(index + i), 16);
            if (digit < 0) {
                throw error("\\u 뒤에 16진수 4자리가 필요합니다");
            }
            code = code * 16 + digit;
        }
        index += 4;
        return (char) code;
    }

    private Object readLiteral(String word, Object value) {
        if (!text.startsWith(word, index)) {
            throw error("알 수 없는 값입니다");
        }
        index += word.length();
        return value;
    }

    private BigDecimal readNumber() {
        int start = index;
        consumeIf('-');
        readIntegerPart();
        if (consumeIf('.')) {
            readDigits("소수점 뒤에 숫자가 필요합니다");
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            index++;
            if (!consumeIf('+')) {
                consumeIf('-');
            }
            readDigits("지수에 숫자가 필요합니다");
        }
        try {
            return new BigDecimal(text.substring(start, index));
        } catch (NumberFormatException e) {
            throw error("숫자를 읽을 수 없습니다");
        }
    }

    private void readIntegerPart() {
        if (consumeIf('0')) {
            return;
        }
        readDigits("값이 필요합니다");
    }

    private void readDigits(String message) {
        int start = index;
        while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
            index++;
        }
        if (index == start) {
            throw error(message);
        }
    }

    private void skipBlank() {
        while (index < text.length()) {
            char letter = text.charAt(index);
            if (letter != ' ' && letter != '\t' && letter != '\n' && letter != '\r') {
                return;
            }
            index++;
        }
    }

    private boolean consumeIf(char letter) {
        if (index < text.length() && text.charAt(index) == letter) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char letter) {
        if (!consumeIf(letter)) {
            throw error("'" + letter + "'가 필요합니다");
        }
    }

    private JsonParseException error(String message) {
        return new JsonParseException("[parse] " + message + " (" + index + "번째 글자)");
    }
}
