package minidbms.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SqlFileSplitter(파일 나누기)와 TablePrinter(표 출력) 검증.
 */
class CliHelpersTest {

    @Test
    void 세미콜론으로_문장을_나누고_끝의_세미콜론을_남긴다() {
        assertEquals(List.of("SELECT * FROM a;", "SELECT * FROM b;"), trimAll(SqlFileSplitter.split("SELECT * FROM a;\nSELECT * FROM b;\n")));
    }

    @Test
    void 문자열_안의_세미콜론과_하이픈_주석은_나누지_않는다() {
        List<String> statements = SqlFileSplitter.split("INSERT INTO t (a) VALUES ('x;y--z'); -- 뒤 주석 ; 무시\nSELECT * FROM t;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'x;y--z'"));
    }

    @Test
    void 빈_문장과_빈_파일은_건너뛴다() {
        assertEquals(List.of(), SqlFileSplitter.split(""));
        assertEquals(List.of(), SqlFileSplitter.split(";;\n -- 주석\n;"));
    }

    @Test
    void 세미콜론_없이_끝난_문장도_돌려준다() {
        assertEquals(List.of("SELECT * FROM t"), trimAll(SqlFileSplitter.split("SELECT * FROM t")));
    }

    @Test
    void 한글은_두_칸으로_세어_표의_줄_길이를_맞춘다() {
        assertEquals(2, TablePrinter.displayWidth("가"));
        assertEquals(1, TablePrinter.displayWidth("a"));

        List<String> lines = TablePrinter.format(List.of("id", "name"), List.of(List.of(1, "키보드"), List.of(2, "ab")));

        int width = TablePrinter.displayWidth(lines.get(0));
        for (int i = 0; i < lines.size() - 1; i++) {
            assertEquals(width, TablePrinter.displayWidth(lines.get(i)), "줄 " + i);
        }
        assertEquals("2 rows", lines.get(lines.size() - 1));
    }

    @Test
    void 행이_없으면_0_rows다() {
        List<String> lines = TablePrinter.format(List.of("id"), List.of());

        assertEquals("0 rows", lines.get(lines.size() - 1));
    }

    private static List<String> trimAll(List<String> statements) {
        return statements.stream().map(String::trim).toList();
    }
}
