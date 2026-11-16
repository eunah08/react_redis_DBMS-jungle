package minidbms.sql;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 문자열을 문장 구조(Statement)로 바꾼다. (협약 7장, 8.1)
 * 순서 : 토큰화 → 구문 검증 → 파싱 결과 생성. 파일·네트워크·인덱스를 모른다. (협약 6장)
 * 값은 SQL에 적힌 모양 그대로 담는다. 문자열은 String, 정수는 Long (long에 안 들어가면 BigInteger).
 * 스키마 검사는 engine이 한다.
 */
public final class SqlParser {

    private final String sqlText;
    private final List<SqlToken> tokens;
    private int cursor;

    private SqlParser(String sqlText, List<SqlToken> tokens) {
        this.sqlText = sqlText;
        this.tokens = tokens;
    }

    /**
     * SQL 문장 하나를 파싱한다.
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 파싱 결과
     * @throws SqlSyntaxException 구문 오류, 세미콜론 누락, 지원하지 않는 문장, 문장이 여러 개인 경우
     */
    public static Statement parseSql(String sqlText) {
        SqlParser parser = new SqlParser(sqlText, SqlTokenizer.tokenize(sqlText));
        return parser.parseStatement();
    }

    private Statement parseStatement() {
        SqlToken first = peek();
        Statement statement;
        if (isKeyword(first, "INSERT")) {
            statement = parseInsert();
        } else if (isKeyword(first, "SELECT")) {
            statement = parseSelect();
        } else if (first.type() == TokenType.END || first.type() == TokenType.SEMICOLON) {
            throw error("parseStatement", "빈 문장입니다", first);
        } else {
            throw error("parseStatement", "INSERT 또는 SELECT만 지원합니다", first);
        }
        expectEndOfStatement();
        return statement;
    }

    private void expectEndOfStatement() {
        SqlToken token = peek();
        if (token.type() != TokenType.SEMICOLON) {
            throw error("parseStatement", "문장은 세미콜론(;)으로 끝나야 합니다", token);
        }
        cursor++;
        if (peek().type() != TokenType.END) {
            throw error("parseStatement", "SQL은 한 번에 한 문장만 실행할 수 있습니다", peek());
        }
    }

    private Statement parseInsert() {
        cursor++;
        expectKeyword("INTO", "parseInsert");
        String tableName = expect(TokenType.IDENTIFIER, "parseInsert", "테이블 이름이 필요합니다").text();
        expect(TokenType.LEFT_PAREN, "parseInsert", "컬럼 목록의 여는 괄호가 필요합니다");
        List<String> columns = parseIdentifierList("parseInsert");
        expect(TokenType.RIGHT_PAREN, "parseInsert", "컬럼 목록의 닫는 괄호가 필요합니다");
        expectKeyword("VALUES", "parseInsert");
        expect(TokenType.LEFT_PAREN, "parseInsert", "VALUES 뒤에 괄호가 필요합니다");
        List<Object> values = parseValueList();
        expect(TokenType.RIGHT_PAREN, "parseInsert", "값 목록의 닫는 괄호가 필요합니다");
        return new Statement(StatementType.INSERT, tableName, columns, values, null);
    }

    private Statement parseSelect() {
        cursor++;
        List<String> columns = parseSelectColumns();
        expectKeyword("FROM", "parseSelect");
        String tableName = expect(TokenType.IDENTIFIER, "parseSelect", "테이블 이름이 필요합니다").text();
        Condition condition = null;
        if (isKeyword(peek(), "WHERE")) {
            cursor++;
            condition = parseCondition();
        }
        return new Statement(StatementType.SELECT, tableName, columns, null, condition);
    }

    private List<String> parseSelectColumns() {
        if (peek().type() == TokenType.STAR) {
            cursor++;
            return List.of(Statement.ALL_COLUMNS);
        }
        return parseIdentifierList("parseSelect");
    }

    private Condition parseCondition() {
        String columnName = expect(TokenType.IDENTIFIER, "parseCondition", "WHERE 뒤에 컬럼 이름이 필요합니다").text();
        expect(TokenType.EQUALS, "parseCondition", "WHERE는 '=' 비교만 지원합니다");
        return new Condition(columnName, parseValue("parseCondition"));
    }

    private List<String> parseIdentifierList(String functionName) {
        List<String> names = new ArrayList<>();
        names.add(expect(TokenType.IDENTIFIER, functionName, "컬럼 이름이 필요합니다").text());
        while (peek().type() == TokenType.COMMA) {
            cursor++;
            names.add(expect(TokenType.IDENTIFIER, functionName, "쉼표 뒤에 컬럼 이름이 필요합니다").text());
        }
        return names;
    }

    private List<Object> parseValueList() {
        List<Object> values = new ArrayList<>();
        values.add(parseValue("parseInsert"));
        while (peek().type() == TokenType.COMMA) {
            cursor++;
            values.add(parseValue("parseInsert"));
        }
        return values;
    }

    private Object parseValue(String functionName) {
        SqlToken token = peek();
        if (token.type() == TokenType.STRING) {
            cursor++;
            return token.text();
        }
        if (token.type() == TokenType.INTEGER) {
            cursor++;
            return toInteger(token.text());
        }
        throw error(functionName, "문자열 또는 정수 값이 필요합니다", token);
    }

    private static Object toInteger(String text) {
        BigInteger number = new BigInteger(text);
        if (number.bitLength() < Long.SIZE) {
            return number.longValue();
        }
        return number;
    }

    private SqlToken expect(TokenType type, String functionName, String message) {
        SqlToken token = peek();
        if (token.type() != type) {
            throw error(functionName, message, token);
        }
        cursor++;
        return token;
    }

    private void expectKeyword(String keyword, String functionName) {
        SqlToken token = peek();
        if (!isKeyword(token, keyword)) {
            throw error(functionName, keyword + "가 필요합니다", token);
        }
        cursor++;
    }

    private SqlToken peek() {
        return tokens.get(cursor);
    }

    private static boolean isKeyword(SqlToken token, String keyword) {
        return token.type() == TokenType.KEYWORD && token.text().equals(keyword);
    }

    private SqlSyntaxException error(String functionName, String message, SqlToken token) {
        String where = SqlTokenizer.describePosition(sqlText, token.position());
        String found = token.type() == TokenType.END ? "문장 끝" : "'" + token.text() + "'";
        return new SqlSyntaxException("[" + functionName + "] " + message + " (" + where + ", 현재 " + found + ")");
    }
}
