package minidbms.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 문자열을 토큰 목록으로 나눈다. (협약 7.3 : 토큰화 단계)
 * 공백과 "--" 줄 주석은 건너뛴다. 문자열 안의 세미콜론과 쉼표는 값으로 보존한다.
 * 파일·네트워크를 모른다. (협약 6장)
 */
final class SqlTokenizer {

    private static final String SYMBOLS = "(),;*=";
    private static final char QUOTE = '\'';

    private final String sqlText;
    private int index;

    private SqlTokenizer(String sqlText) {
        this.sqlText = sqlText;
    }

    /**
     * @param sqlText SQL 문자열
     * @return 토큰 목록
     * @throws SqlSyntaxException 문자열이 닫히지 않았거나 지원하지 않는 문자가 있을 때
     */
    static List<Token> tokenize(String sqlText) {
        return new SqlTokenizer(sqlText).readAll();
    }

    private List<Token> readAll() {
        List<Token> tokens = new ArrayList<>();
        while (skipBlank()) {
            tokens.add(readToken());
        }
        return tokens;
    }

    /** 공백과 주석을 건너뛴다. 읽을 글자가 남아 있으면 true. */
    private boolean skipBlank() {
        while (index < sqlText.length()) {
            if (Character.isWhitespace(sqlText.charAt(index))) {
                index++;
            } else if (sqlText.startsWith("--", index)) {
                skipComment();
            } else {
                return true;
            }
        }
        return false;
    }

    private void skipComment() {
        while (index < sqlText.length() && sqlText.charAt(index) != '\n') {
            index++;
        }
    }

    private Token readToken() {
        char current = sqlText.charAt(index);
        if (current == QUOTE) {
            return readString();
        }
        if (isDigit(current) || (current == '-' && isDigitAt(index + 1))) {
            return readNumber();
        }
        if (isWordStart(current)) {
            return readWord();
        }
        if (SYMBOLS.indexOf(current) >= 0) {
            index++;
            return new Token(TokenType.SYMBOL, String.valueOf(current), index - 1);
        }
        throw error("[tokenize] 지원하지 않는 문자입니다: '" + current + "'", index);
    }

    private Token readString() {
        int start = index;
        StringBuilder text = new StringBuilder();
        index++;
        while (index < sqlText.length()) {
            char current = sqlText.charAt(index);
            if (current == QUOTE && isQuoteAt(index + 1)) {
                text.append(QUOTE);
                index += 2;
            } else if (current == QUOTE) {
                index++;
                return new Token(TokenType.STRING, text.toString(), start);
            } else {
                text.append(current);
                index++;
            }
        }
        throw error("[readString] 문자열의 닫는 작은따옴표가 없습니다", start);
    }

    private Token readNumber() {
        int start = index;
        index++;
        while (index < sqlText.length() && isDigit(sqlText.charAt(index))) {
            index++;
        }
        if (index < sqlText.length() && isWordPart(sqlText.charAt(index))) {
            throw error("[readNumber] 숫자 뒤에 글자가 붙어 있습니다", index);
        }
        return new Token(TokenType.NUMBER, sqlText.substring(start, index), start);
    }

    private Token readWord() {
        int start = index;
        while (index < sqlText.length() && isWordPart(sqlText.charAt(index))) {
            index++;
        }
        return new Token(TokenType.WORD, sqlText.substring(start, index), start);
    }

    private boolean isQuoteAt(int position) {
        return position < sqlText.length() && sqlText.charAt(position) == QUOTE;
    }

    private boolean isDigitAt(int position) {
        return position < sqlText.length() && isDigit(sqlText.charAt(position));
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isWordStart(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || character == '_';
    }

    private static boolean isWordPart(char character) {
        return isWordStart(character) || isDigit(character);
    }

    private static SqlSyntaxException error(String message, int position) {
        return new SqlSyntaxException(message + " (" + (position + 1) + "번째 글자)");
    }
}
