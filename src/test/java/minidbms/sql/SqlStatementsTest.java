package minidbms.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlStatementsTest {
    @Test
    void separatesOnlyOutsideStringsAndComments() {
        String sqlText = "-- ; ignored\nINSERT INTO products (name, price) VALUES ('it''s;😀', 1);"
                + "-- tail;\nSELECT * FROM products; -- final";
        List<String> statements = SqlStatements.split(sqlText);
        assertEquals(2, statements.size());
        assertEquals("it's;😀", SqlParser.parseSql(statements.get(0)).values().get(0));
        assertEquals(StatementType.SELECT, SqlParser.parseSql(statements.get(1)).type());
    }

    @Test
    void keepsBadTailUntilEarlierStatementsHaveExecuted() {
        List<String> statements = SqlStatements.split("SELECT * FROM products; INSERT 'unfinished");
        assertEquals(2, statements.size());
        assertEquals(StatementType.SELECT, SqlParser.parseSql(statements.get(0)).type());
        assertThrows(SqlParseException.class, () -> SqlParser.parseSql(statements.get(1)));
        assertEquals(List.of(), SqlStatements.split(" \n-- just a comment ;\n"));
        assertEquals(2, SqlStatements.split(";;").size());
    }
}
