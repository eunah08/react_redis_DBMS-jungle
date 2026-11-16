package minidbms.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 스키마 파일 문법과 이름 검증을 담당한다. 형식은 구현 노트에 고정한다. */
final class SchemaReader {
    static final int MAX_RECORD_BYTES = 1048576;
    private static final int PRODUCT_NAME_BYTES = 32;

    private SchemaReader() { }

    static boolean isTableName(String tableName) {
        return tableName != null && tableName.matches("[a-z0-9_]+");
    }

    static Schema read(Path dataDir, String tableName) {
        if (!isTableName(tableName)) { throw StorageException.unknownTable(); }
        Path path = dataDir.resolve(tableName + ".schema");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw StorageException.unknownTable();
        }
        try {
            List<Column> columns = parseColumns(Files.readAllLines(path));
            Schema schema = new Schema(tableName, columns);
            if (tableName.equals("products")) { validateProducts(schema); }
            return schema;
        } catch (IOException | IllegalArgumentException failure) {
            throw new StorageException("readSchema", failure);
        }
    }

    private static List<Column> parseColumns(List<String> lines) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(Schema.ID_COLUMN_NAME, ColumnType.INT, 0));
        Set<String> names = new HashSet<>();
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) { continue; }
            Column column = parseColumn(text);
            if (!names.add(column.columnName())) { throw new IllegalArgumentException("중복 컬럼"); }
            if (column.columnName().equals(Schema.ID_COLUMN_NAME)) {
                if (column.type() != ColumnType.INT) { throw new IllegalArgumentException("id 자료형"); }
            } else { columns.add(column); }
        }
        long bytes = columns.stream().mapToLong(column -> Integer.BYTES
                + (column.type() == ColumnType.STRING ? column.maxBytes() : 0)).sum();
        if (bytes > MAX_RECORD_BYTES) { throw new IllegalArgumentException("행 크기 초과"); }
        return List.copyOf(columns);
    }

    private static Column parseColumn(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2 || !isTableName(parts[0])) {
            throw new IllegalArgumentException("컬럼 이름 또는 자료형");
        }
        if (parts[1].equals("INT") && parts.length == 2) {
            return new Column(parts[0], ColumnType.INT, 0);
        }
        if (parts[1].equals("STRING") && parts.length == 3) {
            int maxBytes = Integer.parseInt(parts[2]);
            if (maxBytes > 0 && maxBytes <= MAX_RECORD_BYTES) {
                return new Column(parts[0], ColumnType.STRING, maxBytes);
            }
        }
        throw new IllegalArgumentException("지원하지 않는 스키마 정의");
    }

    private static void validateProducts(Schema schema) {
        List<Column> expected = List.of(new Column("id", ColumnType.INT, 0),
                new Column("name", ColumnType.STRING, PRODUCT_NAME_BYTES),
                new Column("price", ColumnType.INT, 0));
        if (!schema.columns().equals(expected)) {
            throw new IllegalArgumentException("products 공통 스키마가 다릅니다");
        }
    }
}
