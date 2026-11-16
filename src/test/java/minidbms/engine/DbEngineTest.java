package minidbms.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbEngineTest {
    @TempDir Path dataDir;

    @BeforeEach void schema() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
    }

    @Test
    void fullScanPhaseOneSupportsProjectionAndReorderedColumns() {
        try (DbEngine engine = new DbEngine(dataDir, AccessPath.FULL_SCAN)) {
            assertTrue(engine.execute("SELECT * FROM products;").getRows().isEmpty());
            assertEquals(1, engine.execute("insert into products (price, name) values (7, '키보드');").getId());
            assertEquals(2, engine.execute("INSERT INTO products (name, price) VALUES ('키보드', 8);").getId());
            DbResult result = engine.execute("SELECT name, price FROM products WHERE name = '키보드';");
            assertEquals(List.of(Map.of("name", "키보드", "price", 7),
                    Map.of("name", "키보드", "price", 8)), result.getRows());
            assertEquals(AccessPath.FULL_SCAN, result.getAccessPath());
        }
    }

    @Test
    void restartRebuildsIndexAndContinuesIdsFromPhaseOneFile() {
        try (DbEngine engine = new DbEngine(dataDir, AccessPath.FULL_SCAN)) {
            assertEquals(1, engine.execute("INSERT INTO products (name, price) VALUES ('old', 7);").getId());
        }
        try (DbEngine engine = new DbEngine(dataDir)) {
            DbResult result = engine.execute("SELECT * FROM products WHERE id = 1;");
            assertEquals(AccessPath.INDEX, result.getAccessPath());
            assertEquals(List.of(Map.of("id", 1, "name", "old", "price", 7)), result.getRows());
            assertEquals(2, engine.execute("INSERT INTO products (name, price) VALUES ('new', 8);").getId());
            assertTrue(engine.execute("SELECT * FROM products WHERE id = 0;").getRows().isEmpty());
            assertTrue(engine.execute("SELECT * FROM products WHERE id = -1;").getRows().isEmpty());
        }
    }

    @Test
    void invalidRequestsPreserveFileAndNextId() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            long before = Files.size(dataDir.resolve("products.table"));
            Map<String, ErrorCode> cases = Map.ofEntries(
                    Map.entry("INSERT INTO products (id, name, price) VALUES (1, 'a', 1);", ErrorCode.INVALID_VALUE),
                    Map.entry("INSERT INTO products (name) VALUES ('a');", ErrorCode.INVALID_VALUE),
                    Map.entry("INSERT INTO products (name, name) VALUES ('a', 'b');", ErrorCode.INVALID_VALUE),
                    Map.entry("INSERT INTO products (name, price) VALUES ('a', -1);", ErrorCode.INVALID_VALUE),
                    Map.entry("INSERT INTO products (name, price) VALUES ('a', '1');", ErrorCode.INVALID_VALUE),
                    Map.entry("INSERT INTO products (name, price) VALUES ('a', 2147483648);", ErrorCode.INVALID_VALUE),
                    Map.entry("SELECT * FROM PRODUCTS;", ErrorCode.UNKNOWN_TABLE),
                    Map.entry("SELECT NAME FROM products;", ErrorCode.UNKNOWN_COLUMN),
                    Map.entry("SELECT * FROM products WHERE id = 'a';", ErrorCode.INVALID_VALUE),
                    Map.entry("SELECT * FROM products", ErrorCode.SQL_SYNTAX));
            cases.forEach((sqlText, code) -> assertEquals(code, engine.execute(sqlText).getErrorCode(), sqlText));
            assertEquals(before, Files.size(dataDir.resolve("products.table")));
            assertEquals(1, engine.execute("INSERT INTO products (name, price) VALUES ('ok', 1);").getId());
        }
    }

    @Test
    void utf8ByteLimitAndEmptyStringAreExact() {
        try (DbEngine engine = new DbEngine(dataDir)) {
            assertTrue(engine.execute("INSERT INTO products (name, price) VALUES ('" + "😀".repeat(8) + "', 0);").isSuccess());
            assertEquals(ErrorCode.INVALID_VALUE, engine.execute(
                    "INSERT INTO products (name, price) VALUES ('" + "😀".repeat(9) + "', 0);").getErrorCode());
            assertEquals(2, engine.execute("INSERT INTO products (name, price) VALUES ('', 2147483647);").getId());
            assertEquals("", engine.execute("SELECT name FROM products WHERE id = 2;").getRows().get(0).get("name"));
        }
    }

    @Test
    void forcedScanMatchesIndexForEveryBoundary() {
        try (DbEngine writer = new DbEngine(dataDir)) {
            for (int id = 1; id <= 200; id++) {
                assertTrue(writer.execute("INSERT INTO products (name, price) VALUES ('p" + id + "', " + id + ");").isSuccess());
            }
        }
        try (DbEngine index = new DbEngine(dataDir); DbEngine scan = new DbEngine(dataDir, AccessPath.FULL_SCAN)) {
            for (int id : new int[] {0, 1, 100, 200, 201}) {
                String sqlText = "SELECT * FROM products WHERE id = " + id + ";";
                assertEquals(index.execute(sqlText).getRows(), scan.execute(sqlText).getRows());
                assertEquals(AccessPath.INDEX, index.execute(sqlText).getAccessPath());
                assertEquals(AccessPath.FULL_SCAN, scan.execute(sqlText).getAccessPath());
            }
        }
    }
}
