package minidbms.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 협약 7.2, 9.1 : 문장 경계 식별.
 */
class SqlSplitterTest {

    @Test
    void 세미콜론으로_문장을_나눈다() {
        List<String> statements = SqlSplitter.splitStatements("SELECT * FROM a; SELECT * FROM b;\nSELECT * FROM c;");

        assertEquals(3, statements.size());
        assertEquals("SELECT * FROM a;", statements.get(0));
        assertEquals(" SELECT * FROM b;", statements.get(1));
    }

    @Test
    void 문자열과_주석_안의_세미콜론은_경계가_아니다() {
        String sqlText = "INSERT INTO t (a) VALUES ('오''늘; 구름');\n-- 주석; 안의 세미콜론\nSELECT * FROM t;";

        List<String> statements = SqlSplitter.splitStatements(sqlText);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("오''늘; 구름"));
    }

    @Test
    void 빈_파일_공백_주석_세미콜론뿐인_파일은_문장이_없다() {
        assertTrue(SqlSplitter.splitStatements("").isEmpty());
        assertTrue(SqlSplitter.splitStatements("  \n\t ").isEmpty());
        assertTrue(SqlSplitter.splitStatements("-- 주석뿐\n-- 또 주석").isEmpty());
        assertTrue(SqlSplitter.splitStatements(";;;  ;").isEmpty());
    }

    @Test
    void 세미콜론_없이_끝난_마지막_문장은_그대로_돌려준다() {
        List<String> statements = SqlSplitter.splitStatements("SELECT * FROM a; SELECT * FROM b");

        assertEquals(2, statements.size());
        assertEquals(" SELECT * FROM b", statements.get(1));
    }

    @Test
    void 닫히지_않은_따옴표는_마지막_문장으로_남아_파서가_오류를_낸다() {
        List<String> statements = SqlSplitter.splitStatements("SELECT * FROM a; INSERT INTO t (a) VALUES ('x; y);");

        assertEquals(2, statements.size());
    }

    @Test
    void 마지막_문장_뒤의_주석은_문장이_아니다() {
        List<String> statements = SqlSplitter.splitStatements("SELECT * FROM a; -- 끝");

        assertEquals(1, statements.size());
    }
}
