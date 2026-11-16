package minidbms.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 문자열을 토큰 목록으로 나눈다. 따옴표 안의 글자는 값으로 보존하고 "--" 줄 주석은 버린다.
 * (협약 7.2, 7.3) 파일·네트워크·인덱스를 모른다. (협약 6장)
 */
final class SqlTokenizer {

    private static final Set<String> KEYWORDS =
            Set.of("INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE");
    private static final char QUOTE = '\'';

    private final String sqlText;
    private int index;

    private SqlTokenizer(String sqlText) {
        this.sqlText = sqlText;
    }

    /**
     * 토큰으로 나눈다. 마지막에는 항상 END 토큰이 붙는다.
     *
     * @param sqlText SQL 원문
     * @return 토큰 목록
     * @throws SqlSyntaxException 알 수 없는 글자, 닫히지 않은 따옴표
     */
    static List<SqlToken> tokenize(String sqlText) {
        return new SqlTokenizer(sqlText).readAllTokens();
    }

    /**
     * 원문 위치를 "N번째 줄 M번째 칸"으로 바꾼다. 오류 메시지용이다.
     *
     * @param sqlText  SQL 원문
     * @param position 0부터 센 글자 위치
     * @return 위치 설명
     */
    static String describePosition(String sqlText, int position) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < position && i < sqlText.length(); i++) {
            if (sqlText.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return line + "번째 줄 " + column + "번째 칸";
    }

    private List<SqlToken> readAllTokens() {
        List<SqlToken> tokens = new ArrayList<>();
        while (true) {
            skipBlankAndComments();
            if (index >= sqlText.length()) {
                tokens.add(new SqlToken(TokenType.END, "", sqlText.length()));
                return tokens;
            }
            tokens.add(readNextToken());
        }
    }

    private void skipBlankAndComments() {
        while (index < sqlText.length()) {
            char letter = sqlText.charAt(index);
            if (Character.isWhitespace(letter)) {
                index++;
            } else if (sqlText.startsWith("--", index)) {
                skipLineComment();
            } else {
                return;
            }
        }
    }

    private void skipLineComment() {
        while (index < sqlText.length() && sqlText.charAt(index) != '\n') {
            index++;
        }
    }

    private SqlToken readNextToken() {
        char letter = sqlText.charAt(index);
        if (letter == QUOTE) {
            return readString();
        }
        if (isDigit(letter) || (letter == '-' && hasDigitAt(index + 1))) {
            return readInteger();
        }
        if (isWordStart(letter)) {
            return readWord();
        }
        return readSymbol(letter);
    }

    private SqlToken readSymbol(char letter) {
        TokenType type = switch (letter) {
            case '(' -> TokenType.LEFT_PAREN;
            case ')' -> TokenType.RIGHT_PAREN;
            case ',' -> TokenType.COMMA;
            case '*' -> TokenType.STAR;
            case '=' -> TokenType.EQUALS;
            case ';' -> TokenType.SEMICOLON;
            default -> throw new SqlSyntaxException("[tokenize] 사용할 수 없는 글자 '" + letter
                    + "' (" + describePosition(sqlText, index) + ")");
        };
        SqlToken token = new SqlToken(type, String.valueOf(letter), index);
        index++;
        return token;
    }

    private SqlToken readWord() {
        int start = index;
        while (index < sqlText.length() && isWordPart(sqlText.charAt(index))) {
            index++;
        }
        String word = sqlText.substring(start, index);
        String upperWord = word.toUpperCase(Locale.ROOT);
        if (KEYWORDS.contains(upperWord)) {
            return new SqlToken(TokenType.KEYWORD, upperWord, start);
        }
        return new SqlToken(TokenType.IDENTIFIER, word, start);
    }

    private SqlToken readInteger() {
        int start = index;
        if (sqlText.charAt(index) == '-') {
            index++;
        }
        while (index < sqlText.length() && isDigit(sqlText.charAt(index))) {
            index++;
        }
        if (index < sqlText.length() && isWordStart(sqlText.charAt(index))) {
            throw new SqlSyntaxException("[tokenize] 숫자 뒤에 글자가 붙어 있습니다 ("
                    + describePosition(sqlText, start) + ")");
        }
        return new SqlToken(TokenType.INTEGER, sqlText.substring(start, index), start);
    }

    private SqlToken readString() {
        int start = index;
        StringBuilder value = new StringBuilder();
        index++;
        while (index < sqlText.length()) {
            char letter = sqlText.charAt(index);
            if (letter != QUOTE) {
                value.append(letter);
                index++;
            } else if (index + 1 < sqlText.length() && sqlText.charAt(index + 1) == QUOTE) {
                value.append(QUOTE);
                index += 2;
            } else {
                index++;
                return new SqlToken(TokenType.STRING, value.toString(), start);
            }
        }
        throw new SqlSyntaxException("[tokenize] 닫히지 않은 따옴표입니다 ("
                + describePosition(sqlText, start) + ")");
    }

    private boolean hasDigitAt(int position) {
        return position < sqlText.length() && isDigit(sqlText.charAt(position));
    }

    private static boolean isDigit(char letter) {
        return letter >= '0' && letter <= '9';
    }

    private static boolean isWordStart(char letter) {
        return (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z') || letter == '_';
    }

    private static boolean isWordPart(char letter) {
        return isWordStart(letter) || isDigit(letter);
    }
}
