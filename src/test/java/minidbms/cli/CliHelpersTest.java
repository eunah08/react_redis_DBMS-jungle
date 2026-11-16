package minidbms.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TablePrinter(표 출력) 검증. 문장 나누기는 sql/SqlSplitterTest가 맡는다.
 */
class CliHelpersTest {

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
}
