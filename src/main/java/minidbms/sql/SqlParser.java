package minidbms.sql;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SQL 문자열을 문장 구조(Statement)로 바꾼다. (협약 7장, 8.1)
 * 순서 : 토큰화 → 구문 검증 → 파싱 결과 생성. 파일·네트워크·인덱스를 모른다. (협약 6장)
 * 값은 SQL에 적힌 모양 그대로 담는다. 문자열은 String, 정수는 Long이다.
 * long 범위를 넘는 정수는 BigInteger로 담아 engine이 범위 오류로 처리하게 한다.
 */
public final class SqlParser {

    private static final Set<String> RESERVED_WORDS =
            Set.of("INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE");

    private final List<Token> tokens;
    private int index;

    private SqlParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * SQL 문장 하나를 파싱한다.
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 파싱 결과
     * @throws SqlSyntaxException 구문 오류, 세미콜론 누락, 지원 범위를 벗어난 문장, 문장이 둘 이상일 때
     */
    public static Statement parseSql(String sqlText) {
        List<Token> tokens = SqlTokenizer.tokenize(sqlText);
        return new SqlParser(tokens).parseStatement();
    }

    private Statement parseStatement() {
        if (tokens.isEmpty()) {
            throw error("[parseStatement] SQL 문장이 비어 있습니다");
        }
        Statement statement;
        if (isKeyword("INSERT")) {
            statement = parseInsert();
        } else if (isKeyword("SELECT")) {
            statement = parseSelect();
        } else {
            throw error("[parseStatement] INSERT 또는 SELECT만 지원합니다");
        }
        expectEndOfStatement();
        return statement;
    }

    private void expectEndOfStatement() {
        expectSymbol(";", "[parseStatement] 문장은 세미콜론(;)으로 끝나야 합니다");
        if (index < tokens.size()) {
            throw error("[parseStatement] 세미콜론 뒤에 다른 내용이 있습니다. 한 번에 한 문장만 실행할 수 있습니다");
        }
    }

    private Statement parseInsert() {
        expectKeyword("INSERT", "[parseInsert] INSERT가 필요합니다");
        expectKeyword("INTO", "[parseInsert] INSERT 뒤에 INTO가 필요합니다");
        String tableName = readIdentifier("[parseInsert] 테이블 이름이 필요합니다");
        List<String> columns = readParenList(
                () -> readIdentifier("[parseInsert] 컬럼 이름이 필요합니다"), "[parseInsert] 컬럼 목록");
        expectKeyword("VALUES", "[parseInsert] 컬럼 목록 뒤에 VALUES가 필요합니다");
        List<Object> values = readParenList(
                () -> readValue("[parseInsert]"), "[parseInsert] VALUES 목록");
        return new Statement(StatementType.INSERT, tableName, columns, values, null);
    }

    private Statement parseSelect() {
        expectKeyword("SELECT", "[parseSelect] SELECT가 필요합니다");
        List<String> columns = readSelectColumns();
        expectKeyword("FROM", "[parseSelect] 컬럼 목록 뒤에 FROM이 필요합니다");
        String tableName = readIdentifier("[parseSelect] 테이블 이름이 필요합니다");
        Condition condition = null;
        if (isKeyword("WHERE")) {
            condition = readCondition();
        }
        return new Statement(StatementType.SELECT, tableName, columns, List.of(), condition);
    }

    private List<String> readSelectColumns() {
        if (isSymbol("*")) {
            index++;
            return List.of(Statement.ALL_COLUMNS);
        }
        List<String> columns = new ArrayList<>();
        columns.add(readIdentifier("[parseSelect] * 또는 컬럼 이름이 필요합니다"));
        while (isSymbol(",")) {
            index++;
            columns.add(readIdentifier("[parseSelect] 쉼표 뒤에 컬럼 이름이 필요합니다"));
        }
        return columns;
    }

    private Condition readCondition() {
        expectKeyword("WHERE", "[readCondition] WHERE가 필요합니다");
        String columnName = readIdentifier("[readCondition] WHERE 뒤에 컬럼 이름이 필요합니다");
        expectSymbol("=", "[readCondition] 조건은 '컬럼 = 값' 하나만 지원합니다");
        Object value = readValue("[readCondition]");
        return new Condition(columnName, value);
    }

    private <T> List<T> readParenList(Supplier<T> readItem, String description) {
        expectSymbol("(", description + "은 여는 괄호 '('로 시작해야 합니다");
        List<T> items = new ArrayList<>();
        items.add(readItem.get());
        while (isSymbol(",")) {
            index++;
            items.add(readItem.get());
        }
        expectSymbol(")", description + "은 닫는 괄호 ')'로 끝나야 합니다");
        return items;
    }

    private String readIdentifier(String message) {
        Token token = peek();
        boolean isName = token != null && token.type() == TokenType.WORD
                && !RESERVED_WORDS.contains(token.text().toUpperCase());
        if (!isName) {
            throw error(message);
        }
        index++;
        return token.text();
    }

    private Object readValue(String functionName) {
        Token token = peek();
        if (token != null && token.type() == TokenType.STRING) {
            index++;
            return token.text();
        }
        if (token != null && token.type() == TokenType.NUMBER) {
            index++;
            return toInteger(token.text());
        }
        throw error(functionName + " 값은 작은따옴표 문자열 또는 정수여야 합니다");
    }

    private static Object toInteger(String numberText) {
        try {
            return Long.parseLong(numberText);
        } catch (NumberFormatException e) {
            return new BigInteger(numberText);
        }
    }

    private Token peek() {
        return index < tokens.size() ? tokens.get(index) : null;
    }

    private boolean isSymbol(String symbol) {
        Token token = peek();
        return token != null && token.isSymbol(symbol);
    }

    private boolean isKeyword(String keyword) {
        Token token = peek();
        return token != null && token.isKeyword(keyword);
    }

    private void expectSymbol(String symbol, String message) {
        if (!isSymbol(symbol)) {
            throw error(message);
        }
        index++;
    }

    private void expectKeyword(String keyword, String message) {
        if (!isKeyword(keyword)) {
            throw error(message);
        }
        index++;
    }

    private SqlSyntaxException error(String message) {
        Token token = peek();
        String where = token == null ? "문장 끝" : (token.position() + 1) + "번째 글자";
        return new SqlSyntaxException(message + " (" + where + ")");
    }
}
