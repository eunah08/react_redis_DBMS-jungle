package minidbms.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 파일의 글을 세미콜론 기준으로 문장마다 나눈다. (협약 8.1 : cli가 나눈다)
 * 작은따옴표 문자열 안의 세미콜론은 나누지 않는다. "--" 줄 주석은 버린다.
 * 각 문장은 끝의 세미콜론을 포함한다. 세미콜론 없이 끝난 마지막 문장도 그대로 돌려줘서
 * 엔진이 SQL_SYNTAX로 알리게 한다. 내용 없는 문장(;만 있는 것)은 건너뛴다.
 */
final class SqlFileSplitter {

    private final String fileText;
    private final List<String> statements = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private int index;
    private boolean isInString;

    private SqlFileSplitter(String fileText) {
        this.fileText = fileText;
    }

    /**
     * @param fileText SQL 파일 전체 글
     * @return 문장 목록
     */
    static List<String> split(String fileText) {
        return new SqlFileSplitter(fileText).splitAll();
    }

    private List<String> splitAll() {
        while (index < fileText.length()) {
            readNext();
        }
        addCurrentIfNotBlank();
        return statements;
    }

    private void readNext() {
        char character = fileText.charAt(index);
        if (isInString) {
            isInString = character != '\'';
            current.append(character);
            index++;
        } else if (fileText.startsWith("--", index)) {
            skipComment();
        } else if (character == ';') {
            current.append(character);
            index++;
            addCurrentIfNotBlank();
        } else {
            isInString = character == '\'';
            current.append(character);
            index++;
        }
    }

    private void skipComment() {
        while (index < fileText.length() && fileText.charAt(index) != '\n') {
            index++;
        }
    }

    /** 모은 글이 ";"뿐이거나 비어 있으면 버리고, 아니면 문장으로 낸다. */
    private void addCurrentIfNotBlank() {
        String text = current.toString().strip();
        current.setLength(0);
        if (!text.isEmpty() && !text.equals(";")) {
            statements.add(text);
        }
    }
}
