package minidbms.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import minidbms.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 협약 9.1, 9.2, 10.4, 11.2 Phase 1·2, 13장 : 엔진의 실행·검증·인덱스 연결.
 */
class DbEngineTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private DbEngine engine;

    @BeforeEach
    void setUp() {
        dataDir = TestSupport.prepareDataDir(tempDir);
        engine = new DbEngine(dataDir);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    // ---- Phase 1 : INSERT / SELECT ----

    @Test
    void INSERT는_id_1부터_이어서_부여한다() {
        assertEquals(1, insert("키보드", 35000));
        assertEquals(2, insert("마우스", 12000));
        assertEquals(3, insert("키보드", 35000));
    }

    @Test
    void SELECT_별표는_id가_맨_앞이고_id_오름차순이다() {
        insert("키보드", 35000);
        insert("마우스", 12000);

        DbResult result = TestSupport.requireSuccess(engine.execute("SELECT * FROM products;"));

        assertEquals(List.of("id", "name", "price"), result.getColumnNames());
        assertEquals(List.of(List.of(1, "키보드", 35000), List.of(2, "마우스", 12000)), result.getRows());
    }

    @Test
    void 컬럼_목록은_적은_순서로_돌려준다() {
        insert("키보드", 35000);

        DbResult result = TestSupport.requireSuccess(engine.execute("SELECT price, name FROM products;"));

        assertEquals(List.of("price", "name"), result.getColumnNames());
        assertEquals(List.of(List.of(35000, "키보드")), result.getRows());
    }

    @Test
    void 컬럼_순서가_스키마와_달라도_INSERT된다() {
        DbResult inserted = TestSupport.requireSuccess(
                engine.execute("INSERT INTO products (price, name) VALUES (500, '펜');"));

        assertEquals(1, inserted.getInsertedId());
        assertEquals(List.of(List.of(1, "펜", 500)),
                TestSupport.requireSuccess(engine.execute("SELECT * FROM products;")).getRows());
    }

    @Test
    void 빈_테이블과_일치하지_않는_조건은_빈_결과다() {
        assertEquals(List.of(), rows("SELECT * FROM products;"));
        insert("키보드", 35000);

        assertEquals(List.of(), rows("SELECT * FROM products WHERE price = 1;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE name = '없음';"));
    }

    @Test
    void 같은_값을_가진_행이_여러_개면_모두_돌려준다() {
        insert("키보드", 35000);
        insert("마우스", 1);
        insert("키보드", 35000);

        assertEquals(List.of(List.of(1, "키보드", 35000), List.of(3, "키보드", 35000)),
                rows("SELECT * FROM products WHERE name = '키보드';"));
        assertEquals(2, rows("SELECT * FROM products WHERE price = 35000;").size());
    }

    @Test
    void 문자열_값의_특수문자가_그대로_저장되고_조회된다() {
        TestSupport.requireSuccess(engine.execute("INSERT INTO products (name, price) VALUES ('오''늘; 구름,😀', 1);"));

        assertEquals(List.of(List.of(1, "오'늘; 구름,😀", 1)), rows("SELECT * FROM products;"));
        assertEquals(1, rows("SELECT * FROM products WHERE name = '오''늘; 구름,😀';").size());
    }

    @Test
    void 키워드_대소문자가_섞여도_실행된다() {
        TestSupport.requireSuccess(engine.execute("insert into products (name, price) values ('a', 1);"));

        assertEquals(1, rows("SeLeCt * FrOm products WhErE id = 1;").size());
    }

    @Test
    void 다시_열어도_데이터가_남고_id가_이어진다() {
        insert("키보드", 35000);
        insert("마우스", 12000);
        engine.close();

        engine = new DbEngine(dataDir);

        assertEquals(2, rows("SELECT * FROM products;").size());
        assertEquals(3, insert("펜", 1));
    }

    @Test
    void 정수와_문자열_경계값을_받는다() {
        assertEquals(1, insert("a".repeat(32), 0));
        assertEquals(2, insert("가".repeat(10) + "ab", Integer.MAX_VALUE));
        assertEquals(3, insert("", 5));

        assertEquals(List.of(2, "가".repeat(10) + "ab", Integer.MAX_VALUE),
                rows("SELECT * FROM products WHERE id = 2;").get(0));
    }

    // ---- 오류 코드 (협약 10.4) ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "SELECT * FROM products|SQL_SYNTAX",
            "SELECT * FROM products WHERE price > 1;|SQL_SYNTAX",
            "SELECT * FROM products; SELECT * FROM products;|SQL_SYNTAX",
            "INSERT INTO products (name, price) VALUES ('a', 1)|SQL_SYNTAX",
            "SELECT * FROM nothing;|UNKNOWN_TABLE",
            "SELECT * FROM Products;|UNKNOWN_TABLE",
            "SELECT * FROM ../products;|SQL_SYNTAX",
            "INSERT INTO nothing (a) VALUES (1);|UNKNOWN_TABLE",
            "SELECT nothing FROM products;|UNKNOWN_COLUMN",
            "SELECT Name FROM products;|UNKNOWN_COLUMN",
            "SELECT * FROM products WHERE nothing = 1;|UNKNOWN_COLUMN",
            "INSERT INTO products (name, nothing) VALUES ('a', 1);|UNKNOWN_COLUMN",
            "INSERT INTO products (id, name, price) VALUES (1, 'a', 1);|INVALID_VALUE",
            "INSERT INTO products (name) VALUES ('a');|INVALID_VALUE",
            "INSERT INTO products (price) VALUES (1);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a');|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a', 1, 2);|INVALID_VALUE",
            "INSERT INTO products (name, name, price) VALUES ('a', 'b', 1);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES (1, 1);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a', 'b');|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a', -1);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a', 2147483648);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('a', 99999999999999999999999);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1);|INVALID_VALUE",
            "INSERT INTO products (name, price) VALUES ('가가가가가가가가가가가', 1);|INVALID_VALUE",
            "SELECT * FROM products WHERE id = 'a';|INVALID_VALUE",
            "SELECT * FROM products WHERE name = 5;|INVALID_VALUE",
            "SELECT name, name FROM products;|SQL_SYNTAX",
    })
    void 잘못된_입력은_파일을_바꾸지_않고_오류_코드를_돌려준다(String sqlText, ErrorCode expected) {
        DbResult result = engine.execute(sqlText);

        assertTrue(result.isError(), sqlText);
        assertEquals(expected, result.getErrorCode(), sqlText + " -> " + result.getMessage());
        assertTrue(result.getMessage().startsWith("["), result.getMessage());
        assertEquals(List.of(), rows("SELECT * FROM products;"));
        assertEquals(1, insert("확인", 1), "실패한 INSERT가 id를 소비하면 안 된다");
    }

    @Test
    void 짝_없는_서로게이트는_INVALID_VALUE다() {
        DbResult result = engine.execute(new minidbms.sql.Statement(minidbms.sql.StatementType.INSERT, "products",
                List.of("name", "price"), List.of("\ud800", 1L), null), true);

        assertEquals(ErrorCode.INVALID_VALUE, result.getErrorCode());
    }

    @Test
    void null_SQL은_SQL_SYNTAX다() {
        assertEquals(ErrorCode.SQL_SYNTAX, engine.execute((String) null).getErrorCode());
    }

    // ---- Phase 2 : 인덱스 ----

    @Test
    void WHERE_id는_인덱스를_쓰고_다른_조건과_조건_없음은_선형_탐색이다() {
        insert("키보드", 35000);

        assertEquals(AccessPath.INDEX, engine.execute("SELECT * FROM products WHERE id = 1;").getAccessPath());
        assertEquals(AccessPath.FULL_SCAN,
                engine.execute("SELECT * FROM products WHERE name = '키보드';").getAccessPath());
        assertEquals(AccessPath.FULL_SCAN, engine.execute("SELECT * FROM products;").getAccessPath());
        assertEquals(AccessPath.FULL_SCAN,
                engine.execute("SELECT * FROM products WHERE id = 1;", false).getAccessPath());
    }

    @Test
    void 인덱스와_강제_선형_탐색의_결과가_같다() {
        for (int i = 1; i <= 300; i++) {
            insert("item" + i, i * 3);
        }

        for (int id : new int[] {-5, 0, 1, 2, 150, 299, 300, 301, 100000}) {
            String sqlText = "SELECT * FROM products WHERE id = " + id + ";";
            DbResult indexed = engine.execute(sqlText, true);
            DbResult scanned = engine.execute(sqlText, false);
            assertEquals(scanned.getRows(), indexed.getRows(), "id=" + id);
            assertEquals(id >= 1 && id <= 300 ? 1 : 0, indexed.getRows().size(), "id=" + id);
        }
    }

    @Test
    void 없는_id와_범위를_벗어난_id는_빈_결과다() {
        assertEquals(List.of(), rows("SELECT * FROM products WHERE id = 1;"));
        insert("a", 1);

        assertEquals(List.of(), rows("SELECT * FROM products WHERE id = 0;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE id = -3;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE id = 2;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE id = 99999999999;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE price = -1;"));
        assertEquals(List.of(), rows("SELECT * FROM products WHERE price = 99999999999999999999;"));
    }

    @Test
    void 인덱스_조회에도_컬럼_선택이_적용된다() {
        insert("키보드", 35000);

        DbResult result = TestSupport.requireSuccess(engine.execute("SELECT name FROM products WHERE id = 1;"));

        assertEquals(AccessPath.INDEX, result.getAccessPath());
        assertEquals(List.of(List.of("키보드")), result.getRows());
    }

    @Test
    void 다시_열면_인덱스가_다시_만들어진다() {
        for (int i = 1; i <= 500; i++) {
            insert("item" + i, i);
        }
        engine.close();

        engine = new DbEngine(dataDir);

        assertEquals(500, engine.getIndexSize("products"));
        assertTrue(engine.getIndexHeight("products") >= 2);
        DbResult result = engine.execute("SELECT * FROM products WHERE id = 250;");
        assertEquals(AccessPath.INDEX, result.getAccessPath());
        assertEquals(List.of(List.of(250, "item250", 250)), result.getRows());
        assertEquals(501, insert("item501", 501));
    }

    // ---- 테이블 여러 개, 손상 ----

    @Test
    void 테이블이_여러_개여도_서로_영향이_없다() {
        TestSupport.writeSchema(dataDir, "notes", "title string 20\n");
        engine.close();
        engine = new DbEngine(dataDir);

        insert("키보드", 1);
        TestSupport.requireSuccess(engine.execute("INSERT INTO notes (title) VALUES ('메모');"));

        assertEquals(1, rows("SELECT * FROM products;").size());
        assertEquals(List.of(List.of(1, "메모")), rows("SELECT * FROM notes;"));
    }

    @Test
    void 엔진이_열린_뒤에_추가된_스키마도_사용할_수_있다() {
        TestSupport.writeSchema(dataDir, "late", "title string 20\n");

        assertEquals(1, TestSupport.requireSuccess(engine.execute("INSERT INTO late (title) VALUES ('x');")).getInsertedId());
    }

    @Test
    void 손상된_테이블은_그_테이블만_INTERNAL_ERROR다() throws IOException {
        TestSupport.writeSchema(dataDir, "notes", "title string 20\n");
        engine.close();
        Files.write(dataDir.resolve("notes.tbl"), new byte[] {9, 9, 9});

        engine = new DbEngine(dataDir);

        assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute("SELECT * FROM notes;").getErrorCode());
        assertEquals(ErrorCode.INTERNAL_ERROR,
                engine.execute("INSERT INTO notes (title) VALUES ('x');").getErrorCode());
        assertEquals(1, insert("정상", 1));
    }

    @Test
    void 데이터_폴더가_없으면_만든다() {
        Path newDir = tempDir.resolve("fresh").resolve("deeper");

        DbEngine fresh = new DbEngine(newDir);
        try {
            assertTrue(Files.isDirectory(newDir));
            assertEquals(ErrorCode.UNKNOWN_TABLE, fresh.execute("SELECT * FROM products;").getErrorCode());
        } finally {
            fresh.close();
        }
    }

    @Test
    void 성공_결과는_오류_필드가_비어_있다() {
        DbResult result = TestSupport.requireSuccess(engine.execute("SELECT * FROM products;"));

        assertNull(result.getErrorCode());
        assertNull(result.getMessage());
    }

    private int insert(String name, int price) {
        String escapedName = name.replace("'", "''");
        DbResult result = TestSupport.requireSuccess(engine.execute(
                "INSERT INTO products (name, price) VALUES ('" + escapedName + "', " + price + ");"));
        return result.getInsertedId();
    }

    private List<List<Object>> rows(String sqlText) {
        return TestSupport.requireSuccess(engine.execute(sqlText)).getRows();
    }
}
