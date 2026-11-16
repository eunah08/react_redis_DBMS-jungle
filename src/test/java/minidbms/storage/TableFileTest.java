package minidbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import minidbms.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 8장, 11.1 단위 테스트(행 저장·읽기), 13장 저장 항목.
 */
class TableFileTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private TableFile tableFile;

    @BeforeEach
    void setUp() {
        dataDir = TestSupport.prepareDataDir(tempDir);
        tableFile = new TableFile(dataDir);
    }

    @AfterEach
    void tearDown() {
        tableFile.close();
    }

    @Test
    void 스키마는_id가_맨_앞에_자동으로_붙는다() {
        Schema schema = tableFile.readSchema("products");

        assertEquals(List.of("id", "name", "price"),
                schema.columns().stream().map(Column::columnName).toList());
        assertEquals(ColumnType.STRING, schema.columns().get(1).type());
        assertEquals(32, schema.columns().get(1).maxBytes());
        assertEquals(ColumnType.INT, schema.columns().get(2).type());
    }

    @Test
    void 행을_추가하면_위치로_다시_읽을_수_있다() {
        long first = tableFile.appendRow("products", List.of(1, "키보드", 35000));
        long second = tableFile.appendRow("products", List.of(2, "마우스", 12000));

        assertTrue(second > first);
        assertEquals(List.of(1, "키보드", 35000), tableFile.readRowAt("products", first));
        assertEquals(List.of(2, "마우스", 12000), tableFile.readRowAt("products", second));
    }

    @Test
    void 쉼표_따옴표_줄바꿈_한글_이모지_빈_문자열이_그대로_저장된다() {
        List<String> names = List.of("a,b", "it's", "\"q\"", "줄\n바꿈", "한글", "😀🎉", "", "a;b", "\\n");
        List<Long> positions = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            positions.add(tableFile.appendRow("products", List.of(i + 1, names.get(i), i)));
        }

        for (int i = 0; i < names.size(); i++) {
            assertEquals(names.get(i), tableFile.readRowAt("products", positions.get(i)).get(1));
        }
    }

    @Test
    void readRows는_추가한_순서대로_행과_위치를_돌려준다() {
        long first = tableFile.appendRow("products", List.of(1, "a", 1));
        long second = tableFile.appendRow("products", List.of(2, "b", 2));

        try (RowCursor cursor = tableFile.readRows("products")) {
            StoredRow row1 = cursor.next();
            StoredRow row2 = cursor.next();

            assertEquals(first, row1.position());
            assertEquals(List.of(2, "b", 2), row2.row());
            assertEquals(second, row2.position());
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void 빈_테이블은_행이_없다() {
        try (RowCursor cursor = tableFile.readRows("products")) {
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void 닫고_다시_열어도_행이_남아_있고_이어서_추가된다() {
        tableFile.appendRow("products", List.of(1, "a", 1));
        tableFile.close();

        TableFile reopened = new TableFile(dataDir);
        try {
            long position = reopened.appendRow("products", List.of(2, "b", 2));
            assertEquals(List.of(2, "b", 2), reopened.readRowAt("products", position));
            assertEquals(2, countRows(reopened));
        } finally {
            reopened.close();
        }
    }

    @Test
    void 마지막_행이_덜_쓰인_파일은_열_때_잘라내고_이전_행은_살아_있다() throws IOException {
        tableFile.appendRow("products", List.of(1, "a", 1));
        tableFile.appendRow("products", List.of(2, "b", 2));
        tableFile.close();
        Path tableFilePath = dataDir.resolve("products.tbl");
        try (RandomAccessFile file = new RandomAccessFile(tableFilePath.toFile(), "rw")) {
            file.setLength(file.length() - 3);
        }

        TableFile reopened = new TableFile(dataDir);
        try {
            assertEquals(1, countRows(reopened));
            long position = reopened.appendRow("products", List.of(2, "c", 3));
            assertEquals(List.of(2, "c", 3), reopened.readRowAt("products", position));
            assertEquals(2, countRows(reopened));
        } finally {
            reopened.close();
        }
    }

    @Test
    void 파일_머리가_손상되면_열지_않는다() throws IOException {
        tableFile.appendRow("products", List.of(1, "a", 1));
        tableFile.close();
        Files.write(dataDir.resolve("products.tbl"), new byte[] {1, 2, 3});

        TableFile reopened = new TableFile(dataDir);
        try {
            assertThrows(StorageException.class, () -> reopened.readSchema("products"));
        } finally {
            reopened.close();
        }
    }

    @Test
    void 행_내용이_바뀌면_CRC로_알아챈다() throws IOException {
        long position = tableFile.appendRow("products", List.of(1, "abc", 1));
        tableFile.close();
        Path tableFilePath = dataDir.resolve("products.tbl");
        try (RandomAccessFile file = new RandomAccessFile(tableFilePath.toFile(), "rw")) {
            file.seek(position + 4 + 4 + 4 + 4);
            file.write('Z');
        }

        TableFile reopened = new TableFile(dataDir);
        try {
            assertThrows(StorageException.class, () -> reopened.readRowAt("products", position));
            assertThrows(StorageException.class, () -> countRows(reopened));
        } finally {
            reopened.close();
        }
    }

    @Test
    void 스키마가_바뀐_파일은_열지_않는다() {
        tableFile.appendRow("products", List.of(1, "a", 1));
        tableFile.close();
        TestSupport.writeSchema(dataDir, "products", "name string 32\nprice int\nstock int\n");

        TableFile reopened = new TableFile(dataDir);
        try {
            assertThrows(StorageException.class, () -> reopened.readSchema("products"));
        } finally {
            reopened.close();
        }
    }

    @Test
    void 없는_위치나_행_시작이_아닌_위치는_오류다() {
        long position = tableFile.appendRow("products", List.of(1, "a", 1));

        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", 0));
        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", position + 1000));
        assertThrows(StorageException.class, () -> tableFile.readRowAt("products", position + 1));
    }

    @Test
    void rollbackAppend는_방금_추가한_행을_없앤다() {
        tableFile.appendRow("products", List.of(1, "a", 1));
        long second = tableFile.appendRow("products", List.of(2, "b", 2));

        tableFile.rollbackAppend("products", second);

        assertEquals(1, countRows(tableFile));
        long again = tableFile.appendRow("products", List.of(2, "c", 3));
        assertEquals(second, again);
    }

    @Test
    void 없는_테이블과_규칙에_맞지_않는_이름은_TableNotFoundException이다() {
        assertThrows(TableNotFoundException.class, () -> tableFile.readSchema("nothing"));
        assertThrows(TableNotFoundException.class, () -> tableFile.readSchema("Products"));
        assertThrows(TableNotFoundException.class, () -> tableFile.readSchema("../products"));
        assertThrows(TableNotFoundException.class, () -> tableFile.readSchema(""));
    }

    @Test
    void 스키마_파일_형식_오류는_StorageException이다() {
        for (String badSchema : List.of("", "name text 32\n", "name string\n", "name string 0\n",
                "Name int\n", "name int\nname int\n", "id int\n", "name string abc\n", "name int 3\n")) {
            TestSupport.writeSchema(dataDir, "broken", badSchema);
            TableFile other = new TableFile(dataDir);
            try {
                assertThrows(StorageException.class, () -> other.readSchema("broken"), badSchema);
            } finally {
                other.close();
            }
        }
    }

    @Test
    void 스키마의_주석과_빈_줄은_무시한다() {
        TestSupport.writeSchema(dataDir, "notes", "# 설명\n\n  title   string  10  \n");

        Schema schema = tableFile.readSchema("notes");

        assertEquals(2, schema.columns().size());
        assertEquals("title", schema.columns().get(1).columnName());
    }

    @Test
    void 스키마_길이를_넘는_문자열이나_타입이_다른_값은_쓰지_않는다() {
        assertThrows(IllegalArgumentException.class,
                () -> tableFile.appendRow("products", List.of(1, "가".repeat(11), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> tableFile.appendRow("products", List.of(1, 5, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> tableFile.appendRow("products", List.of(1, "a")));
        assertEquals(0, countRows(tableFile));
    }

    private static int countRows(TableFile target) {
        int count = 0;
        try (RowCursor cursor = target.readRows("products")) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }
}
