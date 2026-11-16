package minidbms.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import minidbms.engine.AccessPath;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 협약 10.2, 10.3 : JSON 읽기·쓰기와 이스케이프.
 */
class JsonTest {

    @Test
    void 문자열_값은_이스케이프해서_쓴다() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001한글😀\"", JsonWriter.quote("a\"b\\c\n\t\u0001한글😀"));
    }

    @Test
    void 결과를_공통_JSON으로_바꾼다() {
        assertEquals("{\"id\":7}", JsonWriter.toJson(DbResult.forInsert(7)));
        assertEquals("{\"rows\":[]}",
                JsonWriter.toJson(DbResult.forSelect(List.of("id"), List.of(), AccessPath.INDEX)));
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"a\\\"b\"},{\"id\":2,\"name\":\"\"}]}",
                JsonWriter.toJson(DbResult.forSelect(List.of("id", "name"),
                        List.of(List.of(1, "a\"b"), List.of(2, "")), AccessPath.FULL_SCAN)));
        assertEquals("{\"error\":\"SQL_SYNTAX\"}",
                JsonWriter.toJson(DbResult.forError(ErrorCode.SQL_SYNTAX, "[x] 설명")));
    }

    @Test
    void 요청_JSON의_이스케이프를_읽는다() {
        Object parsed = JsonReader.parse(" {\"sql\" : \"SELECT \\\"a\\\" \\\\ \\n \\u0041\\ud83d\\ude00 \\/\"} ");

        assertEquals(Map.of("sql", "SELECT \"a\" \\ \n A😀 /"), parsed);
    }

    @Test
    void 여러_종류의_값을_읽는다() {
        Object parsed = JsonReader.parse("{\"a\":[1,-2.5e3,true,false,null,{}],\"b\":\"\"}");

        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals(Arrays.asList(new BigDecimal("1"), new BigDecimal("-2.5e3"), true, false, null, Map.of()),
                map.get("a"));
        assertEquals("", map.get("b"));
        assertNull(JsonReader.parse("null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "{", "}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{'a':1}", "{\"a\":1}{",
            "{\"a\":1} x", "[1,]", "\"abc", "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "tru", "01", "1.", "-",
            "{\"a\":1,\"a\":2}", "{\"sql\":\"x\" \"y\":1}", "nul", "+1", "{\"a\":1 \"b\":2}",
    })
    void 잘못된_JSON은_JsonParseException이다(String text) {
        assertThrows(JsonParseException.class, () -> JsonReader.parse(text));
    }

    @Test
    void 너무_깊은_중첩은_스택을_넘기지_않고_오류다() {
        String deep = "[".repeat(5000) + "]".repeat(5000);

        assertThrows(JsonParseException.class, () -> JsonReader.parse(deep));
    }
}
