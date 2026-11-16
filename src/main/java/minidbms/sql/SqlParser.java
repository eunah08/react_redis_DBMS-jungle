package minidbms.sql;

import java.util.ArrayList;
import java.util.List;
import minidbms.sql.SqlLexer.Kind;
import minidbms.sql.SqlLexer.Token;

/** 토큰화 → 구문 검증 → 불변 Statement 생성. 스키마 검증은 엔진이 담당한다. */
public final class SqlParser {
    private final List<Token> tokens;
    private int position;

    private SqlParser(String sqlText) {
        tokens = SqlLexer.tokenize(sqlText);
    }

    /**
     * 세미콜론으로 끝나는 한 문장을 파싱한다. 입력을 변경하거나 자원을 열지 않는다.
     * @param sqlText SQL 한 문장
     * @return 문자열/정수 리터럴을 보존한 Statement
     * @throws SqlParseException 문법 또는 정수 범위 오류
     */
    public static Statement parseSql(String sqlText) {
        SqlParser parser = new SqlParser(sqlText);
        Statement statement;
        if (parser.hasKeyword("INSERT")) { statement = parser.parseInsert(); }
        else if (parser.hasKeyword("SELECT")) { statement = parser.parseSelect(); }
        else { throw parser.syntax(); }
        parser.expectSymbol(";");
        if (parser.current().kind() != Kind.END) { throw parser.syntax(); }
        return statement;
    }

    private Statement parseInsert() {
        expectKeyword("INSERT"); expectKeyword("INTO");
        String tableName = readIdentifier();
        expectSymbol("(");
        List<String> columns = readColumns(true);
        expectSymbol(")"); expectKeyword("VALUES"); expectSymbol("(");
        List<Object> values = new ArrayList<>();
        if (!hasSymbol(")")) {
            do { values.add(readLiteral()); } while (takeSymbol(","));
        }
        expectSymbol(")");
        return new Statement(StatementType.INSERT, tableName, columns, List.copyOf(values), null);
    }

    private Statement parseSelect() {
        expectKeyword("SELECT");
        List<String> columns = takeSymbol("*") ? List.of(Statement.ALL_COLUMNS) : readColumns(false);
        expectKeyword("FROM");
        String tableName = readIdentifier();
        Condition condition = null;
        if (hasKeyword("WHERE")) {
            expectKeyword("WHERE");
            String columnName = readIdentifier();
            expectSymbol("=");
            condition = new Condition(columnName, readLiteral());
        }
        return new Statement(StatementType.SELECT, tableName, columns, List.of(), condition);
    }

    private List<String> readColumns(boolean canBeEmpty) {
        List<String> columns = new ArrayList<>();
        if (canBeEmpty && hasSymbol(")")) { return List.of(); }
        do { columns.add(readIdentifier()); } while (takeSymbol(","));
        return List.copyOf(columns);
    }

    private String readIdentifier() {
        Token token = current();
        if (token.kind() != Kind.WORD && token.kind() != Kind.INTEGER) { throw syntax(); }
        if (token.text().startsWith("-")) { throw syntax(); }
        position++;
        return token.text();
    }

    private Object readLiteral() {
        Token token = current();
        if (token.kind() == Kind.STRING) { position++; return token.text(); }
        if (token.kind() != Kind.INTEGER) { throw syntax(); }
        position++;
        try { return Integer.valueOf(token.text()); }
        catch (NumberFormatException failure) {
            throw new SqlParseException("[parseSql] 정수 범위를 벗어났습니다", true);
        }
    }

    private Token current() { return tokens.get(position); }
    private SqlParseException syntax() { return SqlParseException.syntax(current().position()); }
    private boolean hasKeyword(String keyword) {
        return current().kind() == Kind.WORD && current().text().equalsIgnoreCase(keyword);
    }
    private void expectKeyword(String keyword) {
        if (!hasKeyword(keyword)) { throw syntax(); }
        position++;
    }
    private boolean hasSymbol(String symbol) {
        return current().kind() == Kind.SYMBOL && current().text().equals(symbol);
    }
    private boolean takeSymbol(String symbol) {
        if (!hasSymbol(symbol)) { return false; }
        position++; return true;
    }
    private void expectSymbol(String symbol) {
        if (!takeSymbol(symbol)) { throw syntax(); }
    }
}
