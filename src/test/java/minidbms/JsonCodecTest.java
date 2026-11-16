package minidbms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCodecTest {
    @Test
    void roundTripsEscapesAndUnicode() {
        String value = "한글😀\"\\\n\r\t\b\f\0";
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("value", value); input.put("rows", List.of(Map.of("id", 1)));
        Object parsed = JsonCodec.parse(JsonCodec.toJson(input));
        assertEquals(value, ((Map<?, ?>) parsed).get("value"));
        assertEquals("😀", ((Map<?, ?>) JsonCodec.parse("{\"sql\":\"\\ud83d\\ude00\"}")).get("sql"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{} trailing", "{\"sql\":1,\"sql\":2}",
            "{\"sql\":null,\"s\\u0071l\":\"x\"}", "{\"sql\":\"\\ud800\"}",
            "{\"sql\":\"\\udc00\"}", "{\"sql\":\"line\nfeed\"}", "[1,]", "{\"x\":01}"})
    void rejectsInvalidJsonAndDecodedDuplicateFields(String input) {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse(input));
    }
}
