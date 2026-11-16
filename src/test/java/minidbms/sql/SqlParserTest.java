package minidbms.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 협약 7장, 11.2 (Phase 1 파서), 13장 (파서 엣지 케이스) 검증.
 */
class SqlParserTest {

    @Test
    void INSERT를_파싱한다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('키보드', 35000);");

        assertEquals(StatementType.INSERT, statement.type());
        assertEquals("products", statement.tableName());
        assertEquals(List.of("name", "price"), statement.columns());
        assertEquals(List.of("키보드", 35000L), statement.values());
        assertFalse(statement.hasWhere());
    }

    @Test
    void SELECT_별표를_파싱한다() {
        Statement statement = SqlParser.parseSql("SELECT * FROM products;");

        assertEquals(StatementType.SELECT, statement.type());
        assertEquals(List.of("*"), statement.columns());
        assertNull(statement.condition());
    }

    @Test
    void SELECT_컬럼_목록과_숫자_WHERE를_파싱한다() {
        Statement statement = SqlParser.parseSql("SELECT name, price FROM products WHERE price = 35000;");

        assertEquals(List.of("name", "price"), statement.columns());
        assertTrue(statement.hasWhere());
        assertEquals("price", statement.condition().columnName());
        assertEquals(35000L, statement.condition().value());
    }

    @Test
    void WHERE의_문자열_값을_파싱한다() {
        Statement statement = SqlParser.parseSql("SELECT * FROM products WHERE name = '키보드';");

        assertEquals("키보드", statement.condition().value());
    }

    @Test
    void 키워드는_대소문자와_줄바꿈을_가리지_않는다() {
        Statement statement = SqlParser.parseSql("sElEcT *\n  fRoM products\r\n WhErE id = 1 ;");

        assertEquals(StatementType.SELECT, statement.type());
        assertEquals("id", statement.condition().columnName());
    }

    @Test
    void 컬럼_순서가_스키마와_달라도_적힌_순서_그대로_담는다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (price, name) VALUES (1, 'a');");

        assertEquals(List.of("price", "name"), statement.columns());
        assertEquals(List.of(1L, "a"), statement.values());
    }

    @Test
    void 문자열_안의_세미콜론_쉼표_따옴표_줄바꿈_이모지를_보존한다() {
        Statement statement = SqlParser.parseSql(
                "INSERT INTO products (name, price) VALUES ('오''늘; 구름, 😀\n줄', 1);");

        assertEquals("오'늘; 구름, 😀\n줄", statement.values().get(0));
    }

    @Test
    void 빈_문자열_값을_허용한다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('', 0);");

        assertEquals("", statement.values().get(0));
        assertEquals(0L, statement.values().get(1));
    }

    @Test
    void 음수와_큰_정수를_담는다() {
        Statement negative = SqlParser.parseSql("SELECT * FROM products WHERE price = -5;");
        Statement overInt = SqlParser.parseSql("SELECT * FROM products WHERE price = 2147483648;");
        Statement overLong = SqlParser.parseSql("SELECT * FROM products WHERE price = 99999999999999999999;");

        assertEquals(-5L, negative.condition().value());
        assertEquals(2147483648L, overInt.condition().value());
        assertEquals(new BigInteger("99999999999999999999"), overLong.condition().value());
    }

    @Test
    void 줄_주석은_무시한다() {
        Statement statement = SqlParser.parseSql("-- 주석 ; 입니다\nSELECT * FROM products; -- 뒤 주석");

        assertEquals(StatementType.SELECT, statement.type());
    }

    @Test
    void 따옴표_안의_두_하이픈은_주석이_아니다() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('a--b', 1);");

        assertEquals("a--b", statement.values().get(0));
    }

    @Test
    void 잘못된_문장은_모두_SQL_구문_오류다() {
        List<String> badSqlTexts = List.of(
                "",
                "   ",
                "-- 주석뿐",
                ";",
                "SELECT * FROM products",
                "SELECT * FROM products;;",
                "SELECT * FROM products; SELECT * FROM products;",
                "SELECT * FROM products; garbage",
                "INSERT INTO products (name, price) VALUES ('a, 1);",
                "INSERT INTO products (name, price VALUES ('a', 1);",
                "INSERT INTO products name, price) VALUES ('a', 1);",
                "INSERT INTO products () VALUES ();",
                "INSERT INTO products (name) VALUES ('a', );",
                "INSERT products (name) VALUES ('a');",
                "INSERT INTO products (name) ('a');",
                "SELECT * products;",
                "SELECT FROM products;",
                "SELECT name, FROM products;",
                "SELECT * FROM;",
                "SELECT * FROM products WHERE;",
                "SELECT * FROM products WHERE price > 1;",
                "SELECT * FROM products WHERE price = 1 AND id = 2;",
                "SELECT * FROM products WHERE price = name;",
                "SELECT * FROM products WHERE price = 12abc;",
                "UPDATE products SET price = 1;",
                "DELETE FROM products;",
                "CREATE TABLE products (id INT);",
                "SELECT * FROM products ORDER BY id;",
                "SELECT * FROM select;",
                "SELECT * FROM products WHERE id = @;");

        for (String sqlText : badSqlTexts) {
            assertSyntaxError(sqlText);
        }
    }

    @Test
    void 오류_메시지는_함수이름과_위치를_담는다() {
        SqlSyntaxException error = assertThrows(SqlSyntaxException.class,
                () -> SqlParser.parseSql("SELECT * FROM products WHERE price 1;"));

        assertTrue(error.getMessage().startsWith("[readCondition]"));
        assertTrue(error.getMessage().contains("번째 글자"));
    }

    @Test
    void 버퍼보다_긴_토큰도_처리한다() {
        String longName = "x".repeat(100_000);

        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('" + longName + "', 1);");

        assertEquals(longName, statement.values().get(0));
    }

    private static void assertSyntaxError(String sqlText) {
        try {
            SqlParser.parseSql(sqlText);
        } catch (SqlSyntaxException expected) {
            return;
        }
        fail("구문 오류여야 하는데 통과했습니다: " + sqlText);
    }
}
