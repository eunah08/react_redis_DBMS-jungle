package minidbms.engine;

import minidbms.sql.Condition;
import minidbms.sql.Statement;
import minidbms.storage.Column;
import minidbms.storage.ColumnType;
import minidbms.storage.Schema;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 파싱 결과를 스키마에 맞게 검증하고 자료형을 맞춘다. (협약 9.1 : 스키마 검증)
 * 파일을 건드리지 않는다. 오류는 DbException으로 올린다.
 */
final class StatementValidator {

    static final int ID_COLUMN_INDEX = 0;

    private StatementValidator() {
    }

    /**
     * INSERT 문장으로 파일에 쓸 행을 만든다. 모든 검증을 여기서 마친다.
     *
     * @param schema    테이블 정의
     * @param statement INSERT 파싱 결과
     * @param id        부여할 id
     * @return 스키마 순서의 행 (맨 앞이 id)
     */
    static List<Object> buildInsertRow(Schema schema, Statement statement, int id) {
        List<String> columns = statement.columns();
        List<Object> values = statement.values();
        if (columns.size() != values.size()) {
            throw invalid("[buildInsertRow] 컬럼 수와 값 수가 다릅니다");
        }
        Object[] rowValues = new Object[schema.columns().size()];
        for (int i = 0; i < columns.size(); i++) {
            putValue(schema, rowValues, columns.get(i), values.get(i));
        }
        rowValues[ID_COLUMN_INDEX] = id;
        for (int i = 0; i < rowValues.length; i++) {
            if (rowValues[i] == null) {
                throw invalid("[buildInsertRow] 빠진 컬럼이 있습니다: " + schema.columns().get(i).columnName());
            }
        }
        return new ArrayList<>(List.of(rowValues));
    }

    /**
     * @param schema  테이블 정의
     * @param columns SELECT의 컬럼 목록 (["*"]이면 전체)
     * @return 고른 컬럼의 번호 목록
     */
    static List<Integer> resolveSelectColumns(Schema schema, List<String> columns) {
        List<Integer> columnIndexes = new ArrayList<>();
        if (columns.size() == 1 && columns.get(0).equals(Statement.ALL_COLUMNS)) {
            for (int i = 0; i < schema.columns().size(); i++) {
                columnIndexes.add(i);
            }
            return columnIndexes;
        }
        for (String columnName : columns) {
            columnIndexes.add(requireColumnIndex(schema, columnName));
        }
        return columnIndexes;
    }

    /**
     * @param schema    테이블 정의
     * @param condition WHERE 조건
     * @return 자료형을 맞춘 조건
     */
    static ResolvedCondition resolveCondition(Schema schema, Condition condition) {
        int columnIndex = requireColumnIndex(schema, condition.columnName());
        Column column = schema.columns().get(columnIndex);
        Object value = condition.value();
        if (column.type() == ColumnType.STRING) {
            if (!(value instanceof String)) {
                throw invalid("[resolveCondition] 문자열 컬럼은 문자열과 비교해야 합니다: " + column.columnName());
            }
            return new ResolvedCondition(columnIndex, value, false);
        }
        return resolveIntCondition(columnIndex, column, value);
    }

    private static ResolvedCondition resolveIntCondition(int columnIndex, Column column, Object value) {
        if (isNegativeInteger(value)) {
            return new ResolvedCondition(columnIndex, null, true);
        }
        if (value instanceof Long number && number <= Integer.MAX_VALUE) {
            return new ResolvedCondition(columnIndex, number.intValue(), false);
        }
        throw invalid("[resolveCondition] 정수 컬럼은 0 이상 " + Integer.MAX_VALUE
                + " 이하 정수와 비교해야 합니다: " + column.columnName());
    }

    private static boolean isNegativeInteger(Object value) {
        if (value instanceof Long number) {
            return number < 0;
        }
        return value instanceof BigInteger big && big.signum() < 0;
    }

    /**
     * @param schema     테이블 정의
     * @param columnName 컬럼 이름
     * @return 컬럼 번호. 없으면 -1
     */
    static int findColumnIndex(Schema schema, String columnName) {
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).columnName().equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    private static int requireColumnIndex(Schema schema, String columnName) {
        int columnIndex = findColumnIndex(schema, columnName);
        if (columnIndex < 0) {
            throw new DbException(ErrorCode.UNKNOWN_COLUMN, "[requireColumnIndex] 등록되지 않은 컬럼입니다: " + columnName);
        }
        return columnIndex;
    }

    private static void putValue(Schema schema, Object[] rowValues, String columnName, Object value) {
        if (columnName.equals(Schema.ID_COLUMN_NAME)) {
            throw invalid("[putValue] id는 자동으로 부여되므로 INSERT에 적을 수 없습니다");
        }
        int columnIndex = requireColumnIndex(schema, columnName);
        if (rowValues[columnIndex] != null) {
            throw invalid("[putValue] 같은 컬럼을 두 번 적었습니다: " + columnName);
        }
        rowValues[columnIndex] = convertInsertValue(schema.columns().get(columnIndex), value);
    }

    private static Object convertInsertValue(Column column, Object value) {
        if (column.type() == ColumnType.INT) {
            if (value instanceof Long number && number >= 0 && number <= Integer.MAX_VALUE) {
                return number.intValue();
            }
            throw invalid("[convertInsertValue] 정수 컬럼에는 0 이상 " + Integer.MAX_VALUE
                    + " 이하 정수만 넣을 수 있습니다: " + column.columnName());
        }
        if (!(value instanceof String text)) {
            throw invalid("[convertInsertValue] 문자열 컬럼에는 문자열만 넣을 수 있습니다: " + column.columnName());
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > column.maxBytes()) {
            throw invalid("[convertInsertValue] 문자열이 최대 " + column.maxBytes() + "바이트를 넘습니다: "
                    + column.columnName());
        }
        return text;
    }

    private static DbException invalid(String message) {
        return new DbException(ErrorCode.INVALID_VALUE, message);
    }
}
