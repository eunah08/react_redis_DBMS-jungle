package minidbms.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 스키마 파일 내용을 Schema로 바꾼다.
 *
 * <p>형식 : 한 줄에 컬럼 하나. 빈 줄과 '#'로 시작하는 줄은 무시한다.
 * <pre>
 * name string 32
 * price int
 * </pre>
 * 컬럼 이름은 소문자로 시작하는 소문자·숫자·밑줄이다. string은 UTF-8 최대 바이트 수가 필요하다.
 * id는 적지 않는다. 맨 앞에 자동으로 붙는다. (협약 7.1)
 */
final class SchemaReader {

    private static final Pattern COLUMN_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private SchemaReader() {
    }

    /**
     * 스키마 파일 내용을 해석한다.
     *
     * @param tableName  테이블 이름
     * @param schemaText 스키마 파일 내용
     * @return id 컬럼이 맨 앞에 붙은 테이블 정의
     * @throws StorageException 형식 오류
     */
    static Schema parseSchema(String tableName, String schemaText) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(Schema.ID_COLUMN_NAME, ColumnType.INT, 0));
        Set<String> seenNames = new HashSet<>();
        seenNames.add(Schema.ID_COLUMN_NAME);
        int lineNumber = 0;
        for (String line : schemaText.split("\\R", -1)) {
            lineNumber++;
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            Column column = parseColumnLine(tableName, trimmedLine, lineNumber);
            if (!seenNames.add(column.columnName())) {
                throw failure(tableName, lineNumber, "컬럼 이름이 겹칩니다 (id는 적지 않습니다)");
            }
            columns.add(column);
        }
        if (columns.size() == 1) {
            throw failure(tableName, 0, "컬럼이 하나도 없습니다");
        }
        return new Schema(tableName, List.copyOf(columns));
    }

    private static Column parseColumnLine(String tableName, String line, int lineNumber) {
        String[] parts = line.split("\\s+");
        if (!COLUMN_NAME.matcher(parts[0]).matches()) {
            throw failure(tableName, lineNumber, "컬럼 이름은 소문자·숫자·밑줄이어야 합니다");
        }
        if (parts.length < 2) {
            throw failure(tableName, lineNumber, "'이름 int' 또는 '이름 string 최대바이트' 형식이어야 합니다");
        }
        String typeName = parts[1].toLowerCase(Locale.ROOT);
        if (typeName.equals("int") && parts.length == 2) {
            return new Column(parts[0], ColumnType.INT, 0);
        }
        if (typeName.equals("string") && parts.length == 3) {
            return new Column(parts[0], ColumnType.STRING, parseMaxBytes(tableName, parts[2], lineNumber));
        }
        throw failure(tableName, lineNumber, "'이름 int' 또는 '이름 string 최대바이트' 형식이어야 합니다");
    }

    private static int parseMaxBytes(String tableName, String text, int lineNumber) {
        try {
            int maxBytes = Integer.parseInt(text);
            if (maxBytes > 0) {
                return maxBytes;
            }
        } catch (NumberFormatException e) {
            throw failure(tableName, lineNumber, "최대 바이트 수가 숫자가 아닙니다: " + text);
        }
        throw failure(tableName, lineNumber, "최대 바이트 수는 1 이상이어야 합니다");
    }

    private static StorageException failure(String tableName, int lineNumber, String reason) {
        String where = lineNumber == 0 ? "" : " (" + lineNumber + "번째 줄)";
        return new StorageException("[parseSchema] " + tableName + ".schema 오류" + where + " : " + reason);
    }
}
