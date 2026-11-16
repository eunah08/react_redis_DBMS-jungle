package minidbms.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT 결과를 글자 표로 그린다. 한글처럼 칸 폭이 2인 글자를 폭 2로 세어 열을 맞춘다.
 * 정수는 오른쪽, 문자열은 왼쪽에 맞춘다. (협약 10.1 : 표 모양은 자율)
 */
final class TableFormatter {

    private TableFormatter() {
    }

    /**
     * 표를 그린다. 마지막 줄에 행 수를 쓴다.
     *
     * @param columnNames 컬럼 이름
     * @param rows        행 목록 (정수는 Integer, 문자열은 String)
     * @return 줄 목록
     */
    static List<String> format(List<String> columnNames, List<List<Object>> rows) {
        int[] widths = measureColumnWidths(columnNames, rows);
        String border = buildBorder(widths);
        List<String> lines = new ArrayList<>();
        lines.add(border);
        lines.add(buildLine(widths, new ArrayList<Object>(columnNames), true));
        lines.add(border);
        for (List<Object> row : rows) {
            lines.add(buildLine(widths, row, false));
        }
        if (!rows.isEmpty()) {
            lines.add(border);
        }
        lines.add(rows.size() == 1 ? "1 row" : rows.size() + " rows");
        return lines;
    }

    /**
     * 글자가 터미널에서 차지하는 칸 수를 센다. 한글·한자·전각 문자·이모지는 2칸이다.
     *
     * @param text 글자
     * @return 칸 수
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

    /**
     * 칸에 쓸 글자를 만든다. 줄바꿈·탭 같은 제어 문자는 표 줄을 깨므로 \n, \t, \u0001 처럼 보이게 바꾼다.
     * 저장된 값은 바뀌지 않는다. 표에 그릴 때만 바꾼다.
     *
     * @param cell 칸의 값
     * @return 표에 쓸 글자
     */
    static String toCellText(Object cell) {
        String text = String.valueOf(cell);
        StringBuilder visible = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char letter = text.charAt(i);
            switch (letter) {
                case '\n' -> visible.append("\\n");
                case '\r' -> visible.append("\\r");
                case '\t' -> visible.append("\\t");
                default -> visible.append(Character.isISOControl(letter)
                        ? String.format("\\u%04x", (int) letter) : String.valueOf(letter));
            }
        }
        return visible.toString();
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

    private static int[] measureColumnWidths(List<String> columnNames, List<List<Object>> rows) {
        int[] widths = new int[columnNames.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = displayWidth(columnNames.get(i));
        }
        for (List<Object> row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(toCellText(row.get(i))));
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

    private static String buildLine(int[] widths, List<Object> cells, boolean isHeader) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            Object cell = cells.get(i);
            String text = toCellText(cell);
            int padding = widths[i] - displayWidth(text);
            boolean isRightAligned = !isHeader && cell instanceof Integer;
            line.append(' ');
            line.append(isRightAligned ? " ".repeat(padding) + text : text + " ".repeat(padding));
            line.append(" |");
        }
        return line.toString();
    }
}
