package minidbms.sql;

import java.util.ArrayList;
import java.util.List;

/** CLI용 문장 경계만 찾는다. 뒤의 잘못된 문장이 앞의 실행을 막지 않게 검증은 미룬다. */
public final class SqlStatements {
    private SqlStatements() { }

    /**
     * 문자열/주석 밖의 세미콜론으로 나눈다. 미완성 마지막 문장도 결과에 보존한다.
     * @param sqlText SQL 파일 내용
     * @return 실행 순서의 문장 목록. 공백/주석만 있으면 빈 목록
     */
    public static List<String> split(String sqlText) {
        List<String> statements = new ArrayList<>();
        boolean isQuoted = false;
        boolean isComment = false;
        boolean hasContent = false;
        int start = 0;
        for (int index = 0; index < sqlText.length(); index++) {
            char character = sqlText.charAt(index);
            char next = index + 1 < sqlText.length() ? sqlText.charAt(index + 1) : '\0';
            if (isComment) {
                if (character == '\n' || character == '\r') { isComment = false; }
            } else if (isQuoted) {
                if (character == '\'' && next == '\'') { index++; }
                else if (character == '\'') { isQuoted = false; }
            } else if (character == '-' && next == '-') { isComment = true; index++; }
            else if (character == ';') {
                statements.add(sqlText.substring(start, index + 1).strip());
                start = index + 1; hasContent = false;
            } else if (!Character.isWhitespace(character)) {
                hasContent = true;
                if (character == '\'') { isQuoted = true; }
            }
        }
        if (hasContent) { statements.add(sqlText.substring(start).strip()); }
        return List.copyOf(statements);
    }
}
