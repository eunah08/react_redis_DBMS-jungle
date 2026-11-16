package minidbms.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import minidbms.engine.AccessPath;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 협약 10.3, 10.4 : 응답 JSON 모양과 요청 본문 읽기 검증.
 */
class JsonTest {

    @Test
    void INSERT_결과는_id_하나다() {
        assertEquals("{\"id\":7}", JsonWriter.toJson(DbResult.forInsert(7)));
    }

    @Test
    void SELECT_결과는_컬럼_이름을_키로_한_행_목록이다() {
        DbResult result = DbResult.forSelect(List.of("id", "name"),
                List.of(List.of(1, "키보드"), List.of(2, "a")), AccessPath.FULL_SCAN);

        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\"},{\"id\":2,\"name\":\"a\"}]}", JsonWriter.toJson(result));
    }

    @Test
    void 빈_SELECT는_빈_배열이다() {
        DbResult result = DbResult.forSelect(List.of("id"), List.of(), AccessPath.INDEX);

        assertEquals("{\"rows\":[]}", JsonWriter.toJson(result));
    }

    @Test
    void 오류는_이름만_담는다() {
        assertEquals("{\"error\":\"SQL_SYNTAX\"}", JsonWriter.toJson(DbResult.forError(ErrorCode.SQL_SYNTAX, "자세한 설명")));
        assertEquals("{\"error\":\"SERVER_BUSY\"}", JsonWriter.toErrorJson("SERVER_BUSY"));
    }

    @Test
    void 특수_문자를_이스케이프한다() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\"", JsonWriter.quote("a\"b\\c\n\r\t"));
        assertEquals("\"\\u0001\"", JsonWriter.quote("\u0001"));
        assertEquals("\"한글😀\"", JsonWriter.quote("한글😀"));
    }

    @Test
    void 요청_본문에서_sql_문자열을_읽는다() {
        assertEquals("SELECT * FROM products;", JsonReader.readSqlRequest("{\"sql\":\"SELECT * FROM products;\"}"));
        assertEquals("a\"b\n", JsonReader.readSqlRequest(" {\n \"sql\" : \"a\\\"b\\n\" } "));
        assertEquals("한글😀", JsonReader.readSqlRequest("{\"sql\":\"\\ud55c\\uae00\\ud83d\\ude00\"}"));
        assertEquals("😀", JsonReader.readSqlRequest("{\"sql\":\"😀\"}"));
    }

    @Test
    void 올바르지_않은_본문은_JsonFormatException이다() {
        List<String> bodies = List.of(
                "", "   ", "null", "[]", "\"sql\"", "{", "}", "{}",
                "{\"sql\":1}", "{\"sql\":null}", "{\"sql\":[\"a\"]}",
                "{\"sql\":\"a\"", "{\"sql\":\"a\",}", "{\"sql\":\"a\"} x", "{\"sql\":\"a\"}{}",
                "{\"query\":\"a\"}", "{\"sql\":\"a\",\"sql\":\"b\"}", "{\"sql\":\"a\",\"extra\":1}",
                "{'sql':'a'}", "{sql:\"a\"}", "{\"sql\":\"a\\x\"}", "{\"sql\":\"a\\u12\"}",
                "{\"sql\":\"줄\n바꿈\"}", "{\"sql\":\"a\" \"b\"}");

        for (String body : bodies) {
            assertThrows(JsonFormatException.class, () -> JsonReader.readSqlRequest(body));
        }
    }
}
