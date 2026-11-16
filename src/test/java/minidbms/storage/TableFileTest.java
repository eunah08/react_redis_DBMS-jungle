package minidbms.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableFileTest {
    @TempDir Path dataDir;

    private TableFile openTable() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
        return new TableFile(dataDir);
    }

    @Test
    void persistsExactUtf8RowsAndIndependentBytePositions() throws Exception {
        TableFile tableFile = openTable();
        List<Object> first = List.of(1, "한글,\"'\n😀\0", 0);
        List<Object> second = List.of(2, "", Integer.MAX_VALUE);
        long firstPosition;
        long secondPosition;
        try {
            assertEquals(List.of("id", "name", "price"), tableFile.readSchema("products")
                    .columns().stream().map(Column::columnName).toList());
            firstPosition = tableFile.appendRow("products", first);
            secondPosition = tableFile.appendRow("products", second);
            assertTrue(secondPosition > firstPosition);
        } finally { tableFile.close(); }
        TableFile reopened = new TableFile(dataDir);
        try {
            assertEquals(first, reopened.readRowAt("products", firstPosition));
            assertEquals(second, reopened.readRowAt("products", secondPosition));
            Iterator<StoredRow> rows = reopened.readRows("products");
            assertEquals(new StoredRow(firstPosition, first), rows.next());
            assertEquals(new StoredRow(secondPosition, second), rows.next());
            assertFalse(rows.hasNext());
        } finally { reopened.close(); }
    }

    @Test
    void detectsTruncatedRecordAndBrokenHeader() throws Exception {
        TableFile tableFile = openTable();
        tableFile.appendRow("products", List.of(1, "a", 1));
        tableFile.close();
        Path tablePath = dataDir.resolve("products.table");
        byte[] complete = Files.readAllBytes(tablePath);
        Files.write(tablePath, java.util.Arrays.copyOf(complete, complete.length - 1));
        TableFile truncated = new TableFile(dataDir);
        try { assertThrows(RuntimeException.class, () -> truncated.readRows("products")); }
        finally { truncated.close(); }
        complete[0] ^= 127;
        Files.write(tablePath, complete);
        TableFile broken = new TableFile(dataDir);
        try { assertThrows(RuntimeException.class, () -> broken.readRows("products")); }
        finally { broken.close(); }
    }

    @Test
    void invalidSchemaNamesAndOversizeValuesDoNotAppend() throws Exception {
        TableFile tableFile = openTable();
        try {
            tableFile.appendRow("products", List.of(1, "ok", 1));
            long before = Files.size(dataDir.resolve("products.table"));
            assertThrows(RuntimeException.class, () -> tableFile.appendRow("products",
                    List.of(2, "😀".repeat(9), 1)));
            assertEquals(before, Files.size(dataDir.resolve("products.table")));
            assertThrows(RuntimeException.class, () -> tableFile.readSchema("../products"));
            assertThrows(RuntimeException.class, () -> tableFile.readSchema("missing"));
            assertThrows(RuntimeException.class, () -> tableFile.readRowAt("products", 1));
        } finally { tableFile.close(); }
    }
}
