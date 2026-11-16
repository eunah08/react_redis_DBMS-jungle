package minidbms.engine;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import minidbms.sql.Condition;
import minidbms.sql.Statement;
import minidbms.storage.Column;
import minidbms.storage.ColumnType;
import minidbms.storage.Schema;

/**
 * 파싱 결과를 스키마와 맞춰 검증하고 실행에 쓸 모양으로 바꾼다. (협약 7.3 : 자료형 검사는 engine이 한다)
 * 파일을 건드리지 않는다. 오류는 EngineException으로 알린다.
 */
final class StatementValidator {

    private StatementValidator() {
    }

    /**
     * INSERT의 값을 스키마 순서(id 제외)의 Integer·String 목록으로 바꾼다.
     *
     * @param schema    테이블 정의
     * @param statement INSERT 파싱 결과
     * @return id를 뺀 컬럼 값 목록 (스키마 순서)
     * @throws EngineException UNKNOWN_COLUMN 또는 INVALID_VALUE
     */
    static List<Object> toInsertValues(Schema schema, Statement statement) {
        List<String> columnNames = statement.columns();
        List<Integer> columnIndexes = findInsertColumnIndexes(schema, columnNames);
        if (columnNames.size() != statement.values().size()) {
            throw invalid("toInsertValues", "컬럼 수(" + columnNames.size() + ")와 값 수("
                    + statement.values().size() + ")가 다릅니다");
        }
        Object[] valuesBySchema = new Object[schema.columns().size()];
        for (int i = 0; i < columnIndexes.size(); i++) {
            int columnIndex = columnIndexes.get(i);
            if (valuesBySchema[columnIndex] != null) {
                throw invalid("toInsertValues", "컬럼이 중복되었습니다: " + columnNames.get(i));
            }
            valuesBySchema[columnIndex] = toColumnValue(schema.columns().get(columnIndex), statement.values().get(i));
        }
        return collectValuesWithoutId(schema, valuesBySchema);
    }

    /**
     * SELECT를 검증하고 실행 계획으로 바꾼다.
     *
     * @param schema    테이블 정의
     * @param statement SELECT 파싱 결과
     * @return 실행 계획
     * @throws EngineException UNKNOWN_COLUMN, INVALID_VALUE, SQL_SYNTAX(결과 컬럼 중복)
     */
    static SelectPlan planSelect(Schema schema, Statement statement) {
        List<String> columnNames = new ArrayList<>();
        int[] columnIndexes = findSelectColumnIndexes(schema, statement.columns(), columnNames);
        if (!statement.hasWhere()) {
            return new SelectPlan(columnNames, columnIndexes, SelectPlan.NO_CONDITION, null, false);
        }
        Condition condition = statement.condition();
        int conditionIndex = findColumnIndex(schema, condition.columnName());
        Object conditionValue = toConditionValue(schema.columns().get(conditionIndex), condition.value());
        return new SelectPlan(columnNames, columnIndexes, conditionIndex, conditionValue, conditionValue == null);
    }

    /**
     * WHERE 값을 컬럼과 비교할 모양(Integer 또는 String)으로 바꾼다.
     *
     * @return 비교할 값. 정수 범위를 벗어나 어떤 행과도 같을 수 없으면 null
     * @throws EngineException 컬럼과 값의 타입이 다르면 INVALID_VALUE
     */
    private static Object toConditionValue(Column column, Object value) {
        if (column.type() == ColumnType.STRING) {
            if (!(value instanceof String)) {
                throw invalid("planSelect", column.columnName() + "은 문자열 컬럼이라 문자열과 비교해야 합니다");
            }
            return value;
        }
        if (!(value instanceof Long) && !(value instanceof BigInteger)) {
            throw invalid("planSelect", column.columnName() + "은 정수 컬럼이라 정수와 비교해야 합니다");
        }
        BigInteger number = toBigInteger(value);
        boolean isOutOfRange = number.bitLength() > Integer.SIZE - 1;
        return isOutOfRange ? null : Integer.valueOf(number.intValue());
    }

    private static List<Integer> findInsertColumnIndexes(Schema schema, List<String> columnNames) {
        List<Integer> indexes = new ArrayList<>();
        for (String columnName : columnNames) {
            indexes.add(findColumnIndex(schema, columnName));
        }
        if (indexes.contains(0)) {
            throw invalid("toInsertValues", "id는 직접 지정할 수 없습니다 (자동으로 부여됩니다)");
        }
        return indexes;
    }

    private static int[] findSelectColumnIndexes(Schema schema, List<String> requested, List<String> namesOut) {
        if (requested.size() == 1 && requested.get(0).equals(Statement.ALL_COLUMNS)) {
            int[] all = new int[schema.columns().size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
                namesOut.add(schema.columns().get(i).columnName());
            }
            return all;
        }
        Set<String> seen = new HashSet<>();
        int[] indexes = new int[requested.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = findColumnIndex(schema, requested.get(i));
            if (!seen.add(requested.get(i))) {
                throw new EngineException(ErrorCode.SQL_SYNTAX,
                        "[planSelect] 결과 컬럼이 중복되었습니다: " + requested.get(i));
            }
            namesOut.add(requested.get(i));
        }
        return indexes;
    }

    private static int findColumnIndex(Schema schema, String columnName) {
        int columnIndex = schema.findColumnIndex(columnName);
        if (columnIndex < 0) {
            throw new EngineException(ErrorCode.UNKNOWN_COLUMN,
                    "[findColumnIndex] 등록되지 않은 컬럼입니다: " + columnName);
        }
        return columnIndex;
    }

    private static List<Object> collectValuesWithoutId(Schema schema, Object[] valuesBySchema) {
        List<Object> values = new ArrayList<>(valuesBySchema.length - 1);
        for (int i = 1; i < valuesBySchema.length; i++) {
            if (valuesBySchema[i] == null) {
                throw invalid("toInsertValues", "필수 컬럼이 빠졌습니다: " + schema.columns().get(i).columnName());
            }
            values.add(valuesBySchema[i]);
        }
        return values;
    }

    private static Object toColumnValue(Column column, Object value) {
        if (column.type() == ColumnType.INT) {
            return toIntValue(column, value);
        }
        return toStringValue(column, value);
    }

    private static Integer toIntValue(Column column, Object value) {
        if (!(value instanceof Long) && !(value instanceof BigInteger)) {
            throw invalid("toInsertValues", column.columnName() + "은 정수여야 합니다");
        }
        BigInteger number = toBigInteger(value);
        if (number.signum() < 0 || number.bitLength() > Integer.SIZE - 1) {
            throw invalid("toInsertValues", column.columnName() + "은 0 이상 " + Schema.MAX_INT_VALUE
                    + " 이하여야 합니다");
        }
        return number.intValue();
    }

    private static String toStringValue(Column column, Object value) {
        if (!(value instanceof String text)) {
            throw invalid("toInsertValues", column.columnName() + "은 문자열이어야 합니다");
        }
        int byteCount = countUtf8Bytes(text);
        if (byteCount < 0) {
            throw invalid("toInsertValues", column.columnName() + "에 UTF-8로 저장할 수 없는 글자가 있습니다");
        }
        if (byteCount > column.maxBytes()) {
            throw invalid("toInsertValues", column.columnName() + "은 최대 " + column.maxBytes()
                    + "바이트인데 " + byteCount + "바이트입니다");
        }
        return text;
    }

    /** UTF-8 바이트 수를 센다. 짝 없는 서로게이트처럼 인코딩할 수 없으면 -1. */
    private static int countUtf8Bytes(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            return encoded.remaining();
        } catch (CharacterCodingException exception) {
            return -1;
        }
    }

    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger big) {
            return big;
        }
        return BigInteger.valueOf((Long) value);
    }

    private static EngineException invalid(String functionName, String message) {
        return new EngineException(ErrorCode.INVALID_VALUE, "[" + functionName + "] " + message);
    }
}
