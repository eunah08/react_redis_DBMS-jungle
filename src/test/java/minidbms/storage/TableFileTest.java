package minidbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 8장 저장 규칙 검증 : 스키마 읽기, 행 추가·읽기, 특수 문자열, 재열기, 손상된 파일.
 */
class TableFileTest {

    private static TableFile openProducts(Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        return new TableFile(dataDir);
    }

    private static List<Object> row(int id, String name, int price) {
        return new ArrayList<>(List.of(id, name, price));
    }

    private static List<StoredRow> readAll(TableFile tableFile) {
        List<StoredRow> rows = new ArrayList<>();
        Iterator<StoredRow> iterator = tableFile.readRows("products");
        while (iterator.hasNext()) {
            rows.add(iterator.next());
        }
        return rows;
    }

    @Test
    void 스키마를_읽으면_id가_맨_앞에_붙는다(@TempDir Path dataDir) throws IOException {
        Schema schema = openProducts(dataDir).readSchema("products");

        assertEquals("products", schema.tableName());
        assertEquals(3, schema.columns().size());
        assertEquals(new Column("id", ColumnType.INT, 0), schema.columns().get(0));
        assertEquals(new Column("name", ColumnType.STRING, 32), schema.columns().get(1));
        assertEquals(new Column("price", ColumnType.INT, 0), schema.columns().get(2));
    }

    @Test
    void 스키마_파일의_주석_빈줄_BOM을_무시한다(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("memos.schema"), "﻿# 설명\n\n  text   STRING   10  \n");

        Schema schema = new TableFile(dataDir).readSchema("memos");

        assertEquals(new Column("text", ColumnType.STRING, 10), schema.columns().get(1));
    }

    @Test
    void 없거나_규칙에_맞지_않는_테이블_이름은_TableNotFoundException이다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);

        for (String tableName : List.of("nothing", "Products", "../products", "a b", "", "products.schema")) {
            assertThrows(TableNotFoundException.class, () -> tableFile.readSchema(tableName));
            assertThrows(TableNotFoundException.class, () -> tableFile.readRows(tableName));
        }
    }

    @Test
    void 잘못된_스키마_파일은_StorageException이다(@TempDir Path dataDir) throws IOException {
        List<String> badSchemas = List.of(
                "",
                "name string\n",
                "name string 0\n",
                "name string abc\n",
                "name text 10\n",
                "price int 4\n",
                "Name int\n",
                "price int\nprice int\n",
                "id int\n");

        for (String badSchema : badSchemas) {
            Files.writeString(dataDir.resolve("bad.schema"), badSchema);
            assertThrows(StorageException.class, () -> new TableFile(dataDir).readSchema("bad"));
        }
    }

    @Test
    void 행을_추가하고_위치로_다시_읽는다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);

        long first = tableFile.appendRow("products", row(1, "키보드", 35000));
        long second = tableFile.appendRow("products", row(2, "마우스", 12000));

        assertEquals(RowCodec.HEADER_BYTES, first);
        assertTrue(second > first);
        assertEquals(row(1, "키보드", 35000), tableFile.readRowAt("products", first));
        assertEquals(row(2, "마우스", 12000), tableFile.readRowAt("products", second));
        List<StoredRow> rows = readAll(tableFile);
        assertEquals(2, rows.size());
        assertEquals(first, rows.get(0).position());
        assertEquals(second, rows.get(1).position());
        tableFile.close();
    }

    @Test
    void 쉼표_따옴표_줄바꿈_한글_이모지_빈_문자열이_그대로_저장된다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        List<String> names = List.of("a,b", "say \"hi\"", "it's", "줄\n바꿈", "\r\n", "한글", "😀🎉", "", "a;b", "\\n", "tab\t");

        for (int i = 0; i < names.size(); i++) {
            tableFile.appendRow("products", row(i + 1, names.get(i), i));
        }

        List<StoredRow> rows = readAll(tableFile);
        for (int i = 0; i < names.size(); i++) {
            assertEquals(names.get(i), rows.get(i).row().get(1));
        }
        tableFile.close();
    }

    @Test
    void 다시_열어도_행이_남아_있고_이어서_추가된다(@TempDir Path dataDir) throws IOException {
        TableFile before = openProducts(dataDir);
        before.appendRow("products", row(1, "a", 1));
        before.appendRow("products", row(2, "b", 2));
        before.close();

        TableFile after = new TableFile(dataDir);
        long third = after.appendRow("products", row(3, "c", 3));

        List<StoredRow> rows = readAll(after);
        assertEquals(3, rows.size());
        assertEquals(third, rows.get(2).position());
        assertEquals(row(3, "c", 3), rows.get(2).row());
        after.close();
    }

    @Test
    void 마지막_행이_덜_쓰인_파일은_온전한_행까지만_읽고_이어서_쓸_수_있다(@TempDir Path dataDir) throws IOException {
        TableFile before = openProducts(dataDir);
        before.appendRow("products", row(1, "a", 1));
        before.appendRow("products", row(2, "b", 2));
        before.close();
        Path tablePath = dataDir.resolve("products.tbl");
        Files.write(tablePath, new byte[] {0, 0, 0, 100, 1, 2, 3}, StandardOpenOption.APPEND);

        TableFile after = new TableFile(dataDir);
        assertEquals(2, readAll(after).size());
        long third = after.appendRow("products", row(3, "c", 3));

        assertEquals(row(3, "c", 3), after.readRowAt("products", third));
        assertEquals(3, readAll(after).size());
        assertEquals(Files.size(tablePath), third + (Files.size(tablePath) - third));
        after.close();
    }

    @Test
    void 길이만_쓰이고_내용이_없는_끝부분도_잘라낸다(@TempDir Path dataDir) throws IOException {
        TableFile before = openProducts(dataDir);
        before.appendRow("products", row(1, "a", 1));
        before.close();
        Files.write(dataDir.resolve("products.tbl"), new byte[] {0, 0}, StandardOpenOption.APPEND);

        TableFile after = new TableFile(dataDir);

        assertEquals(1, readAll(after).size());
        after.close();
    }

    @Test
    void 행_내용이_바뀌면_CRC로_알아채고_조용히_넘기지_않는다(@TempDir Path dataDir) throws IOException {
        TableFile before = openProducts(dataDir);
        long first = before.appendRow("products", row(1, "abc", 1));
        before.appendRow("products", row(2, "def", 2));
        before.close();
        flipByte(dataDir.resolve("products.tbl"), first + RowCodec.LENGTH_PREFIX_BYTES + 8);

        TableFile after = new TableFile(dataDir);

        assertThrows(StorageException.class, () -> after.readRowAt("products", first));
        assertThrows(StorageException.class, () -> readAll(after));
        after.close();
    }

    @Test
    void 다른_스키마로_만든_파일은_열지_않는다(@TempDir Path dataDir) throws IOException {
        TableFile before = openProducts(dataDir);
        before.appendRow("products", row(1, "a", 1));
        before.close();
        Files.writeString(dataDir.resolve("products.schema"), "name string 64\nprice int\n");

        assertThrows(StorageException.class, () -> new TableFile(dataDir).readRows("products"));
    }

    private static void flipByte(Path tablePath, long position) throws IOException {
        byte[] bytes = Files.readAllBytes(tablePath);
        bytes[(int) position] ^= 0x55;
        Files.write(tablePath, bytes);
    }

    @Test
    void 헤더가_손상된_파일은_StorageException이다(@TempDir Path dataDir) throws IOException {
        openProducts(dataDir);
        Files.write(dataDir.resolve("products.tbl"), "XXXXXXXXXXXXXXXX".getBytes(StandardCharsets.US_ASCII));

        assertThrows(StorageException.class, () -> new TableFile(dataDir).readRows("products"));
    }

    @Test
    void 헤더보다_짧은_파일도_손상으로_본다(@TempDir Path dataDir) throws IOException {
        openProducts(dataDir);
        Files.write(dataDir.resolve("products.tbl"), new byte[] {'M', 'D'});

        assertThrows(StorageException.class, () -> new TableFile(dataDir).readRows("products"));
    }

    @Test
    void 스키마와_맞지_않는_값은_쓰기_전에_거부하고_파일을_바꾸지_않는다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        tableFile.appendRow("products", row(1, "a", 1));
        long sizeBefore = Files.size(dataDir.resolve("products.tbl"));

        assertThrows(StorageException.class, () -> tableFile.appendRow("products", row(2, "가".repeat(11), 1)));
        assertThrows(StorageException.class, () -> tableFile.appendRow("products", List.of(2, "a")));
        assertThrows(StorageException.class, () -> tableFile.appendRow("products", List.of(2, 5, 1)));
        assertThrows(StorageException.class, () -> tableFile.appendRow("products", List.of(2, "a", "x")));

        assertEquals(sizeBefore, Files.size(dataDir.resolve("products.tbl")));
        tableFile.close();
    }

    @Test
    void 문자열은_정확히_최대_바이트까지_저장한다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        String name = "가".repeat(10) + "ab";

        long position = tableFile.appendRow("products", row(1, name, 1));

        assertEquals(32, name.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(name, tableFile.readRowAt("products", position).get(1));
        tableFile.close();
    }

    @Test
    void 방금_추가한_행을_rollbackAppend로_되돌린다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        tableFile.appendRow("products", row(1, "a", 1));
        long second = tableFile.appendRow("products", row(2, "b", 2));

        tableFile.rollbackAppend("products", second);
        long again = tableFile.appendRow("products", row(2, "c", 3));

        assertEquals(second, again);
        assertEquals(2, readAll(tableFile).size());
        assertEquals(row(2, "c", 3), tableFile.readRowAt("products", again));
        tableFile.close();
    }

    @Test
    void 행의_시작이_아닌_위치나_범위_밖_위치는_StorageException이다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        long position = tableFile.appendRow("products", row(1, "a", 1));

        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", position + 1));
        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", 0));
        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", Files.size(dataDir.resolve("products.tbl"))));
        tableFile.close();
    }

    @Test
    void 덩어리_경계를_넘는_많은_행도_차례로_읽고_위치로_읽는다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        int rowCount = 60_000;
        long[] positions = new long[rowCount];
        for (int i = 0; i < rowCount; i++) {
            positions[i] = tableFile.appendRow("products", row(i + 1, "상품-" + "x".repeat(10) + i, i));
        }
        assertTrue(Files.size(dataDir.resolve("products.tbl")) > 1024 * 1024);

        int expectedId = 1;
        Iterator<StoredRow> iterator = tableFile.readRows("products");
        while (iterator.hasNext()) {
            StoredRow stored = iterator.next();
            assertEquals(expectedId, stored.row().get(0));
            assertEquals(positions[expectedId - 1], stored.position());
            expectedId++;
        }
        assertEquals(rowCount + 1, expectedId);
        assertEquals(12344, tableFile.readRowAt("products", positions[12344]).get(2));
        tableFile.close();
    }

    @Test
    void 읽기_도중_추가된_행은_이미_만든_반복자에_보이지_않는다(@TempDir Path dataDir) throws IOException {
        TableFile tableFile = openProducts(dataDir);
        tableFile.appendRow("products", row(1, "a", 1));

        Iterator<StoredRow> iterator = tableFile.readRows("products");
        tableFile.appendRow("products", row(2, "b", 2));

        assertEquals(1, iterator.next().row().get(0));
        assertTrue(!iterator.hasNext());
        tableFile.close();
    }
}
