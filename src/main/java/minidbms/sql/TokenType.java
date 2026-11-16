package minidbms.sql;

/**
 * SQL 토큰의 종류. (협약 18장 : 키워드·이름·값·기호)
 */
enum TokenType {
    KEYWORD,
    IDENTIFIER,
    INTEGER,
    STRING,
    LEFT_PAREN,
    RIGHT_PAREN,
    COMMA,
    STAR,
    EQUALS,
    SEMICOLON,
    END
}
