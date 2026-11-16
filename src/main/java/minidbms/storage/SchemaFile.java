package minidbms.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 스키마 파일(data/&lt;테이블&gt;.schema)의 글을 Schema로 바꾼다. (협약 7.1)
 * 형식 : 한 줄에 컬럼 하나. 빈 줄과 '#'로 시작하는 줄은 무시한다.
 * <pre>
 * name string 32
 * price int
 * </pre>
 * id 컬럼은 적지 않아도 맨 앞에 자동으로 붙는다.
 */
final class SchemaFile {

    private static final Pattern COLUMN_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_STRING_BYTES = 65535;
    private static final String BYTE_ORDER_MARK = "﻿";

    private SchemaFile() {
    }

    /**
     * @param tableName 테이블 이름
     * @param lines     스키마 파일의 줄들
     * @return id가 맨 앞에 붙은 테이블 정의
     * @throws StorageException 형식이 올바르지 않을 때
     */
    static Schema parse(String tableName, List<String> lines) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(Schema.ID_COLUMN_NAME, ColumnType.INT, 0));
        Set<String> columnNames = new HashSet<>();
        columnNames.add(Schema.ID_COLUMN_NAME);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace(BYTE_ORDER_MARK, "").strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Column column = parseLine(line, i + 1);
            if (!columnNames.add(column.columnName())) {
                throw new StorageException("[parse] 컬럼 이름이 겹칩니다: " + column.columnName()
                        + " (" + (i + 1) + "번째 줄)");
            }
            columns.add(column);
        }
        if (columns.size() == 1) {
            throw new StorageException("[parse] 스키마에 컬럼이 하나도 없습니다: " + tableName);
        }
        return new Schema(tableName, List.copyOf(columns));
    }

    private static Column parseLine(String line, int lineNumber) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2 || !COLUMN_NAME_PATTERN.matcher(parts[0]).matches()) {
            throw lineError(lineNumber, "컬럼 이름 자료형 형식이어야 합니다 (컬럼 이름은 소문자·숫자·밑줄)");
        }
        String typeName = parts[1].toLowerCase();
        if (typeName.equals("int") && parts.length == 2) {
            return new Column(parts[0], ColumnType.INT, 0);
        }
        if (typeName.equals("string") && parts.length == 3) {
            return new Column(parts[0], ColumnType.STRING, parseMaxBytes(parts[2], lineNumber));
        }
        throw lineError(lineNumber, "자료형은 'int' 또는 'string 최대바이트수'여야 합니다");
    }

    private static int parseMaxBytes(String text, int lineNumber) {
        try {
            int maxBytes = Integer.parseInt(text);
            if (maxBytes >= 1 && maxBytes <= MAX_STRING_BYTES) {
                return maxBytes;
            }
        } catch (NumberFormatException e) {
            throw lineError(lineNumber, "문자열 최대 바이트 수가 숫자가 아닙니다: " + text);
        }
        throw lineError(lineNumber, "문자열 최대 바이트 수는 1~" + MAX_STRING_BYTES + "이어야 합니다");
    }

    private static StorageException lineError(int lineNumber, String reason) {
        return new StorageException("[parse] 스키마 " + lineNumber + "번째 줄 오류 : " + reason);
    }
}
