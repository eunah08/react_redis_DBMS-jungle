package minidbms.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import minidbms.sql.StatementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 6장, 10장 : SQL 한 문장이 결과로 바뀌는 흐름, 오류 코드, 인덱스 사용 검증.
 */
class DbEngineTest {

    private static DbEngine newEngine(Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("products.schema"), "name string 8\nprice int\n");
        return new DbEngine(dataDir);
    }

    private static void assertError(DbResult result, ErrorCode expected) {
        assertTrue(result.isError(), "오류여야 합니다");
        assertEquals(expected, result.getErrorCode());
    }

    @Test
    void INSERT는_1부터_이어지는_id를_돌려준다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);

        DbResult first = engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);");
        DbResult second = engine.execute("INSERT INTO products (price, name) VALUES (2, 'b');");

        assertEquals(StatementType.INSERT, first.getType());
        assertEquals(1, first.getInsertedId());
        assertEquals(2, second.getInsertedId());
        engine.close();
    }

    @Test
    void SELECT_별표는_id를_포함한_모든_컬럼을_돌려준다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);
        engine.execute("INSERT INTO products (name, price) VALUES ('abc', 35000);");

        DbResult result = engine.execute("SELECT * FROM products;");

        assertEquals(List.of("id", "name", "price"), result.getColumnNames());
        assertEquals(List.of(List.of(1, "abc", 35000)), result.getRows());
        assertEquals(AccessPath.FULL_SCAN, result.getAccessPath());
        engine.close();
    }

    @Test
    void 컬럼을_고르면_그_순서대로_돌려준다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);
        engine.execute("INSERT INTO products (name, price) VALUES ('a', 5);");

        DbResult result = engine.execute("SELECT price, name FROM products;");

        assertEquals(List.of("price", "name"), result.getColumnNames());
        assertEquals(List.of(List.of(5, "a")), result.getRows());
        engine.close();
    }

    @Test
    void id_조건은_인덱스를_쓰고_다른_조건은_전체_스캔이다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);
        engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);");
        engine.execute("INSERT INTO products (name, price) VALUES ('b', 2);");

        DbResult byId = engine.execute("SELECT * FROM products WHERE id = 2;");
        DbResult byName = engine.execute("SELECT * FROM products WHERE name = 'b';");
        DbResult forced = engine.executeFullScan("SELECT * FROM products WHERE id = 2;");

        assertEquals(AccessPath.INDEX, byId.getAccessPath());
        assertEquals(AccessPath.FULL_SCAN, byName.getAccessPath());
        assertEquals(AccessPath.FULL_SCAN, forced.getAccessPath());
        assertEquals(byId.getRows(), byName.getRows());
        assertEquals(byId.getRows(), forced.getRows());
        engine.close();
    }

    @Test
    void 없는_id와_음수_조건은_빈_결과다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);
        engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);");

        assertEquals(List.of(), engine.execute("SELECT * FROM products WHERE id = 99;").getRows());
        assertEquals(List.of(), engine.execute("SELECT * FROM products WHERE id = -1;").getRows());
        assertEquals(List.of(), engine.execute("SELECT * FROM products WHERE price = -1;").getRows());
        assertFalse(engine.execute("SELECT * FROM products WHERE id = -1;").isError());
        engine.close();
    }

    @Test
    void 오류_코드가_협약대로_나뉜다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);

        assertError(engine.execute("SELEC * FROM products;"), ErrorCode.SQL_SYNTAX);
        assertError(engine.execute("SELECT * FROM nothing;"), ErrorCode.UNKNOWN_TABLE);
        assertError(engine.execute("SELECT * FROM Products;"), ErrorCode.UNKNOWN_TABLE);
        assertError(engine.execute("SELECT color FROM products;"), ErrorCode.UNKNOWN_COLUMN);
        assertError(engine.execute("SELECT * FROM products WHERE color = 1;"), ErrorCode.UNKNOWN_COLUMN);
        assertError(engine.execute("SELECT * FROM products WHERE PRICE = 1;"), ErrorCode.UNKNOWN_COLUMN);
        assertError(engine.execute("INSERT INTO products (name) VALUES ('a');"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES ('a');"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, name) VALUES ('a', 'b');"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (id, name, price) VALUES (1, 'a', 1);"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES ('a', 'x');"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES (1, 1);"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES ('a', -1);"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES ('a', 2147483648);"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("INSERT INTO products (name, price) VALUES ('123456789', 1);"), ErrorCode.INVALID_VALUE);
        assertError(engine.execute("SELECT * FROM products WHERE price = 2147483648;"), ErrorCode.INVALID_VALUE);
        engine.close();
    }

    @Test
    void 실패한_INSERT는_id를_쓰지_않고_저장하지_않는다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);
        engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);");

        engine.execute("INSERT INTO products (name, price) VALUES ('123456789', 1);");
        DbResult next = engine.execute("INSERT INTO products (name, price) VALUES ('b', 2);");

        assertEquals(2, next.getInsertedId());
        assertEquals(2, engine.execute("SELECT * FROM products;").getRows().size());
        engine.close();
    }

    @Test
    void 상한_값과_정확한_최대_길이를_저장한다(@TempDir Path dataDir) throws IOException {
        DbEngine engine = newEngine(dataDir);

        DbResult result = engine.execute("INSERT INTO products (name, price) VALUES ('12345678', 2147483647);");

        assertFalse(result.isError());
        assertEquals(List.of(List.of(1, "12345678", 2147483647)),
                engine.execute("SELECT * FROM products WHERE id = 1;").getRows());
        engine.close();
    }

    @Test
    void 엔진을_다시_열면_행과_id가_이어진다(@TempDir Path dataDir) throws IOException {
        DbEngine before = newEngine(dataDir);
        before.execute("INSERT INTO products (name, price) VALUES ('a', 1);");
        before.execute("INSERT INTO products (name, price) VALUES ('b', 2);");
        before.close();

        DbEngine after = new DbEngine(dataDir);
        DbResult third = after.execute("INSERT INTO products (name, price) VALUES ('c', 3);");

        assertEquals(3, third.getInsertedId());
        DbResult byId = after.execute("SELECT * FROM products WHERE id = 2;");
        assertEquals(AccessPath.INDEX, byId.getAccessPath());
        assertEquals(List.of(List.of(2, "b", 2)), byId.getRows());
        after.close();
    }

    @Test
    void 테이블이_여러_개여도_서로_섞이지_않는다(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("products.schema"), "name string 8\n");
        Files.writeString(dataDir.resolve("users.schema"), "email string 20\n");
        DbEngine engine = new DbEngine(dataDir);

        engine.execute("INSERT INTO products (name) VALUES ('p');");
        engine.execute("INSERT INTO users (email) VALUES ('u@x.com');");
        engine.execute("INSERT INTO users (email) VALUES ('v@x.com');");

        assertEquals(1, engine.execute("SELECT * FROM products;").getRows().size());
        assertEquals(2, engine.execute("SELECT * FROM users;").getRows().size());
        assertEquals(2, engine.execute("INSERT INTO users (email) VALUES ('w@x.com');").getInsertedId() - 1);
        engine.close();
    }
}
