package minidbms.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlParserTest {
    @Test
    void parsesInsertWithoutLosingQuotedCharacters() {
        Statement statement = SqlParser.parseSql(
                "-- 시작\nInSeRt INTO products (price, name) VALUES (35000, '오''늘;,\n😀');");
        assertEquals(StatementType.INSERT, statement.type());
        assertEquals("products", statement.tableName());
        assertEquals(List.of("price", "name"), statement.columns());
        assertEquals(List.of(35000, "오'늘;,\n😀"), statement.values());
        assertNull(statement.condition());
    }

    @Test
    void parsesSelectProjectionConditionAndPreservesIdentifierCase() {
        Statement statement = SqlParser.parseSql("select name, price from Products where id = -1;");
        assertEquals(StatementType.SELECT, statement.type());
        assertEquals("Products", statement.tableName());
        assertEquals(List.of("name", "price"), statement.columns());
        assertEquals(new Condition("id", -1), statement.condition());
        assertEquals(List.of("*"), SqlParser.parseSql("SELECT * FROM products;").columns());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ";", "SELECT * FROM products", "SELECT FROM products;",
            "SELECT * products;", "SELECT * FROM products; SELECT * FROM products;",
            "INSERT INTO products (name) VALUES ('open);", "DELETE FROM products;",
            "SELECT * FROM products WHERE id > 1;", "SELECT * FROM products WHERE id = 1 AND id = 2;",
            "INSERT INTO products (name VALUES ('x');", "INSERT INTO products (name) VALUES (1.5);"})
    void rejectsUnsupportedOrIncompleteSyntax(String sqlText) {
        assertThrows(IllegalArgumentException.class, () -> SqlParser.parseSql(sqlText));
    }

    @Test
    void keepsArityValidationInEngine() {
        Statement statement = SqlParser.parseSql("INSERT INTO products (name, price) VALUES ('x');");
        assertEquals(2, statement.columns().size());
        assertEquals(1, statement.values().size());
        assertEquals(List.of(""), SqlParser.parseSql("INSERT INTO products (name) VALUES ('');").values());
    }
}
