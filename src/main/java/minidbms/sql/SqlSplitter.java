package minidbms.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 파일 내용을 세미콜론 기준으로 문장마다 나눈다. (협약 7.2, 9.1)
 * 작은따옴표 문자열 안의 세미콜론과 "--" 주석 안의 세미콜론은 경계로 보지 않는다.
 * 세미콜론만 있는 빈 문장과 주석뿐인 부분은 건너뛴다. 세미콜론 없이 끝난 마지막 문장은
 * 그대로 돌려주므로 parseSql이 SQL_SYNTAX로 알린다.
 */
public final class SqlSplitter {

    private final String sqlText;
    private final List<String> statements = new ArrayList<>();
    private int statementStart;
    private boolean hasContent;
    private int index;

    private SqlSplitter(String sqlText) {
        this.sqlText = sqlText;
    }

    /**
     * 문장으로 나눈다.
     *
     * @param sqlText SQL 파일 내용
     * @return 문장 목록. 세미콜론으로 끝나는 문장은 세미콜론을 포함한다
     */
    public static List<String> splitStatements(String sqlText) {
        SqlSplitter splitter = new SqlSplitter(sqlText);
        splitter.scan();
        return splitter.statements;
    }

    private void scan() {
        while (index < sqlText.length()) {
            char letter = sqlText.charAt(index);
            if (letter == '\'') {
                skipString();
            } else if (sqlText.startsWith("--", index)) {
                skipComment();
            } else if (letter == ';') {
                endStatement();
            } else {
                markContent(letter);
            }
        }
        if (hasContent) {
            statements.add(sqlText.substring(statementStart));
        }
    }

    private void markContent(char letter) {
        if (!Character.isWhitespace(letter)) {
            hasContent = true;
        }
        index++;
    }

    private void endStatement() {
        if (hasContent) {
            statements.add(sqlText.substring(statementStart, index + 1));
        }
        index++;
        statementStart = index;
        hasContent = false;
    }

    private void skipComment() {
        while (index < sqlText.length() && sqlText.charAt(index) != '\n') {
            index++;
        }
    }

    private void skipString() {
        hasContent = true;
        index++;
        while (index < sqlText.length()) {
            if (sqlText.charAt(index) != '\'') {
                index++;
            } else if (index + 1 < sqlText.length() && sqlText.charAt(index + 1) == '\'') {
                index += 2;
            } else {
                index++;
                return;
            }
        }
    }
}
