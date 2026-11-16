package minidbms.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 협약 7장, 11.2 Phase 1, 13장 파서 항목.
 */
class SqlParserTest {

    @Test
    void INSERT를_구조로_바꾼다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('키보드', 35000);");

        assertEquals(StatementType.INSERT, statement.type());
        assertEquals("products", statement.tableName());
        assertEquals(List.of("name", "price"), statement.columns());
        assertEquals(List.of("키보드", 35000L), statement.values());
        assertNull(statement.condition());
    }

    @Test
    void SELECT_별표와_컬럼_목록과_WHERE를_파싱한다() {
        Statement all = SqlParser.parseSql("SELECT * FROM products;");
        Statement some = SqlParser.parseSql("select name, price from products where price = 35000;");
        Statement byName = SqlParser.parseSql("SELECT * FROM products WHERE name = '키보드';");

        assertEquals(List.of("*"), all.columns());
        assertFalse(all.hasWhere());
        assertEquals(List.of("name", "price"), some.columns());
        assertEquals(new Condition("price", 35000L), some.condition());
        assertEquals(new Condition("name", "키보드"), byName.condition());
    }

    @Test
    void 키워드_대소문자가_섞여도_된다() {
        Statement statement = SqlParser.parseSql("InSeRt iNtO products (name) VaLuEs ('a');");

        assertEquals(StatementType.INSERT, statement.type());
    }

    @Test
    void 컬럼_순서는_적힌_그대로_담는다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (price, name) VALUES (10, 'a');");

        assertEquals(List.of("price", "name"), statement.columns());
        assertEquals(List.of(10L, "a"), statement.values());
    }

    @Test
    void 문자열_안의_세미콜론_쉼표_따옴표_줄바꿈_이모지를_보존한다() {
        Statement statement = SqlParser.parseSql(
                "INSERT INTO t (a) VALUES ('오''늘; 구름, \"x\"\n줄 😀');");

        assertEquals("오'늘; 구름, \"x\"\n줄 😀", statement.values().get(0));
    }

    @Test
    void 빈_문자열_값을_허용한다() {
        Statement statement = SqlParser.parseSql("INSERT INTO t (a) VALUES ('');");

        assertEquals("", statement.values().get(0));
    }

    @Test
    void 음수와_큰_정수를_정수로_담는다() {
        Statement negative = SqlParser.parseSql("SELECT * FROM t WHERE a = -5;");
        Statement huge = SqlParser.parseSql("SELECT * FROM t WHERE a = 99999999999999999999;");

        assertEquals(-5L, negative.condition().value());
        assertEquals(new BigInteger("99999999999999999999"), huge.condition().value());
    }

    @Test
    void 줄_주석과_문장_뒤_주석은_무시한다() {
        Statement statement = SqlParser.parseSql("-- 설명\nSELECT * FROM t; -- 끝");

        assertEquals("t", statement.tableName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM products",
            "SELECT * FROM products WHERE name = 'abc;",
            "INSERT INTO t (a, b VALUES (1, 2);",
            "INSERT INTO t (a) VALUES (1;",
            "INSERT INTO t (a) VALUES 1;",
            "SELECT * products;",
            "SELECT FROM products;",
            "SELECT * FROM;",
            "SELECT * FROM t WHERE;",
            "SELECT * FROM t WHERE a;",
            "SELECT * FROM t WHERE a > 1;",
            "SELECT * FROM t WHERE a = 1 AND b = 2;",
            "SELECT * FROM t WHERE a = 1.5;",
            "SELECT * FROM t WHERE a = 12abc;",
            "SELECT * FROM t WHERE a = abc;",
            "SELECT *, name FROM t;",
            "SELECT * FROM t ORDER BY a;",
            "UPDATE t SET a = 1;",
            "DELETE FROM t;",
            "CREATE TABLE t (a int);",
            "INSERT INTO t () VALUES ();",
            "INSERT INTO (a) VALUES (1);",
            "SELECT * FROM t; SELECT * FROM t;",
            "SELECT * FROM 1t;",
            "SELECT * FROM t /* x */;",
            "",
            "   ",
            ";",
            "-- 주석뿐",
    })
    void 잘못된_문장은_SqlSyntaxException이다(String sqlText) {
        SqlSyntaxException error = assertThrows(SqlSyntaxException.class, () -> SqlParser.parseSql(sqlText));

        assertTrue(error.getMessage().startsWith("["), "메시지는 [함수이름]으로 시작해야 한다: " + error.getMessage());
    }

    @Test
    void 오류_메시지에_위치가_들어_있다() {
        SqlSyntaxException error = assertThrows(SqlSyntaxException.class,
                () -> SqlParser.parseSql("SELECT *\nFROM t\nWHERE a > 1;"));

        assertTrue(error.getMessage().contains("3번째 줄"), error.getMessage());
    }
}
