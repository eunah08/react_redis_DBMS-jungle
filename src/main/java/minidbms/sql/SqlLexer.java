package minidbms.sql;

import java.util.ArrayList;
import java.util.List;

/** 파일·스키마를 모르는 SQL 토큰화기. 작은따옴표 문자열과 줄 주석을 처리한다. */
final class SqlLexer {
    enum Kind { WORD, INTEGER, STRING, SYMBOL, END }
    record Token(Kind kind, String text, int position) { }

    private static final String SYMBOLS = "(),*=;";
    private final String sqlText;
    private int position;

    private SqlLexer(String sqlText) {
        this.sqlText = sqlText;
    }

    static List<Token> tokenize(String sqlText) {
        if (sqlText == null) { throw SqlParseException.syntax(0); }
        return new SqlLexer(sqlText).readTokens();
    }

    private List<Token> readTokens() {
        List<Token> tokens = new ArrayList<>();
        while (position < sqlText.length()) {
            char character = sqlText.charAt(position);
            if (Character.isWhitespace(character)) { position++; }
            else if (sqlText.startsWith("--", position)) { skipComment(); }
            else if (character == '\'') { tokens.add(readString()); }
            else if (isNameCharacter(character)) { tokens.add(readWord()); }
            else if (character == '-' && position + 1 < sqlText.length()) {
                tokens.add(readNegativeInteger());
            } else if (SYMBOLS.indexOf(character) >= 0) {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(character), position++));
            } else { throw SqlParseException.syntax(position); }
        }
        tokens.add(new Token(Kind.END, "", position));
        return List.copyOf(tokens);
    }

    private void skipComment() {
        while (position < sqlText.length() && sqlText.charAt(position) != '\n'
                && sqlText.charAt(position) != '\r') { position++; }
    }

    private Token readString() {
        int start = position++;
        StringBuilder value = new StringBuilder();
        while (position < sqlText.length()) {
            char character = sqlText.charAt(position++);
            if (character != '\'') { value.append(character); continue; }
            if (position < sqlText.length() && sqlText.charAt(position) == '\'') {
                value.append('\''); position++;
            } else { return new Token(Kind.STRING, value.toString(), start); }
        }
        throw SqlParseException.syntax(start);
    }

    private Token readWord() {
        int start = position;
        boolean isInteger = true;
        while (position < sqlText.length() && isNameCharacter(sqlText.charAt(position))) {
            char character = sqlText.charAt(position++);
            if (character < '0' || character > '9') { isInteger = false; }
        }
        return new Token(isInteger ? Kind.INTEGER : Kind.WORD,
                sqlText.substring(start, position), start);
    }

    private Token readNegativeInteger() {
        int start = position++;
        while (position < sqlText.length() && sqlText.charAt(position) >= '0'
                && sqlText.charAt(position) <= '9') { position++; }
        if (position == start + 1) { throw SqlParseException.syntax(start); }
        return new Token(Kind.INTEGER, sqlText.substring(start, position), start);
    }

    private boolean isNameCharacter(char character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9' || character == '_';
    }
}
