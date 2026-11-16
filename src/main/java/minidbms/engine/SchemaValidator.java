package minidbms.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import minidbms.sql.Statement;
import minidbms.storage.Column;
import minidbms.storage.ColumnType;
import minidbms.storage.Schema;

/** 파일 변경 전에 값과 스키마를 검증한다. 파서와 저장 계층의 책임을 섞지 않는다. */
final class SchemaValidator {
    record QueryPlan(List<Integer> projection, int conditionIndex, Object conditionValue) { }
    private SchemaValidator() { }

    static List<Object> insertRow(Schema schema, Statement statement, int id) {
        if (statement.columns().size() != statement.values().size()
                || statement.columns().contains(Schema.ID_COLUMN_NAME)) { throw invalidValue(); }
        List<Object> row = new ArrayList<>(java.util.Collections.nCopies(schema.columns().size(), null));
        row.set(0, id);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < statement.columns().size(); index++) {
            String columnName = statement.columns().get(index);
            if (!names.add(columnName)) { throw invalidValue(); }
            int columnIndex = columnIndex(schema, columnName);
            Object value = statement.values().get(index);
            validateValue(schema.columns().get(columnIndex), value, true);
            row.set(columnIndex, value);
        }
        if (row.contains(null)) { throw invalidValue(); }
        return List.copyOf(row);
    }

    static QueryPlan planSelect(Schema schema, Statement statement) {
        List<Integer> projection = new ArrayList<>();
        if (statement.columns().equals(List.of(Statement.ALL_COLUMNS))) {
            for (int index = 0; index < schema.columns().size(); index++) { projection.add(index); }
        } else {
            for (String columnName : statement.columns()) { projection.add(columnIndex(schema, columnName)); }
        }
        int conditionIndex = -1;
        Object value = null;
        if (statement.hasWhere()) {
            conditionIndex = columnIndex(schema, statement.condition().columnName());
            value = statement.condition().value();
            validateValue(schema.columns().get(conditionIndex), value, false);
        }
        return new QueryPlan(List.copyOf(projection), conditionIndex, value);
    }

    private static void validateValue(Column column, Object value, boolean isInsert) {
        if (column.type() == ColumnType.INT) {
            if (!(value instanceof Integer number) || isInsert && number < 0) { throw invalidValue(); }
        } else {
            if (!(value instanceof String text) || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                    || text.getBytes(StandardCharsets.UTF_8).length > column.maxBytes()) {
                throw invalidValue();
            }
        }
    }

    static Map<String, Object> project(Schema schema, List<Object> row, List<Integer> projection) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index : projection) { result.put(schema.columns().get(index).columnName(), row.get(index)); }
        return result;
    }

    private static int columnIndex(Schema schema, String columnName) {
        for (int index = 0; index < schema.columns().size(); index++) {
            if (schema.columns().get(index).columnName().equals(columnName)) { return index; }
        }
        throw new DbFailure(ErrorCode.UNKNOWN_COLUMN, "validateSchema", "등록되지 않은 컬럼입니다");
    }

    static DbFailure invalidValue() {
        return new DbFailure(ErrorCode.INVALID_VALUE, "validateValue", "값의 개수·자료형·범위를 확인해 주세요");
    }
}
