package minidbms.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT 결과를 글자로 그린 표로 바꾼다. (협약 10.1 : 표 모양은 자율)
 * 한글·이모지처럼 화면에서 두 칸을 차지하는 글자를 고려해 칸을 맞춘다.
 * 정수는 오른쪽 맞춤, 문자열은 왼쪽 맞춤이다. 문자열 안의 줄바꿈은 \n 으로 보여 준다.
 */
final class TablePrinter {

    private TablePrinter() {
    }

    /**
     * @param columnNames 컬럼 이름
     * @param rows        행 목록
     * @return 표의 각 줄. 마지막 줄은 "n row(s)"
     */
    static List<String> format(List<String> columnNames, List<List<Object>> rows) {
        int[] widths = measureWidths(columnNames, rows);
        String border = buildBorder(widths);
        List<String> lines = new ArrayList<>();
        lines.add(border);
        lines.add(buildLine(widths, new ArrayList<>(columnNames), false));
        lines.add(border);
        for (List<Object> row : rows) {
            lines.add(buildLine(widths, row, true));
        }
        lines.add(border);
        lines.add(rows.size() + (rows.size() == 1 ? " row" : " rows"));
        return lines;
    }

    private static int[] measureWidths(List<String> columnNames, List<List<Object>> rows) {
        int[] widths = new int[columnNames.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = displayWidth(columnNames.get(i));
        }
        for (List<Object> row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(cellText(row.get(i))));
            }
        }
        return widths;
    }

    private static String buildBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }
        return border.toString();
    }

    private static String buildLine(int[] widths, List<Object> cells, boolean isRow) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            Object cell = cells.get(i);
            String text = cellText(cell);
            String padding = " ".repeat(widths[i] - displayWidth(text));
            boolean isRightAligned = isRow && cell instanceof Integer;
            line.append(' ').append(isRightAligned ? padding + text : text + padding).append(" |");
        }
        return line.toString();
    }

    private static String cellText(Object cell) {
        return String.valueOf(cell).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * @param text 글
     * @return 화면에서 차지하는 칸 수 (한글·한자·전각·이모지는 2칸)
     */
    static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += isWide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }
}
