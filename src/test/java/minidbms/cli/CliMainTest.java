package minidbms.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import minidbms.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 10.1, 11.1 기능 테스트(실제 CLI 흐름), 13장 Phase 1 : 파일·여러 문장·실패 정책.
 */
class CliMainTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "fixtures");

    @TempDir
    Path tempDir;

    private Path dataDir;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errorBytes;

    @BeforeEach
    void setUp() {
        dataDir = TestSupport.prepareDataDir(tempDir);
        outBytes = new ByteArrayOutputStream();
        errorBytes = new ByteArrayOutputStream();
    }

    @Test
    void 공통_fixture의_JSON_출력이_기대_결과와_같다() throws IOException {
        int exitCode = runCli("--data-dir", dataDir.toString(), "--json", FIXTURE_DIR.resolve("basic.sql").toString());

        assertEquals(0, exitCode, stderr());
        assertEquals(Files.readString(FIXTURE_DIR.resolve("basic.expected.jsonl")).trim(), stdout().trim());
    }

    @Test
    void 기본_출력은_표이고_INSERT는_새_id를_보여준다() throws IOException {
        Path sqlFile = writeSql("INSERT INTO products (name, price) VALUES ('키보드', 35000);\n"
                + "SELECT * FROM products;\nSELECT * FROM products WHERE id = 9;");

        int exitCode = runCli("--data-dir", dataDir.toString(), sqlFile.toString());

        assertEquals(0, exitCode, stderr());
        String expected = String.join("\n",
                "inserted id=1",
                "+----+--------+-------+",
                "| id | name   | price |",
                "+----+--------+-------+",
                "|  1 | 키보드 | 35000 |",
                "+----+--------+-------+",
                "1 row",
                "+----+------+-------+",
                "| id | name | price |",
                "+----+------+-------+",
                "0 rows");
        assertEquals(expected, stdout().trim().replace("\r\n", "\n"));
    }

    @Test
    void 중간_문장이_실패하면_거기서_멈추고_앞의_INSERT는_남는다() throws IOException {
        Path sqlFile = writeSql("INSERT INTO products (name, price) VALUES ('a', 1);\n"
                + "SELECT * FROM nothing;\n"
                + "INSERT INTO products (name, price) VALUES ('b', 2);");

        int exitCode = runCli("--data-dir", dataDir.toString(), "--json", sqlFile.toString());

        assertEquals(1, exitCode);
        assertEquals("{\"id\":1}", stdout().trim());
        assertEquals("{\"error\":\"UNKNOWN_TABLE\"}", stderr().trim());
        Path checkFile = writeSql("SELECT * FROM products;");
        outBytes.reset();
        assertEquals(0, runCli("--data-dir", dataDir.toString(), "--json", checkFile.toString()));
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"a\",\"price\":1}]}", stdout().trim());
    }

    @Test
    void 표_모드의_오류는_원인과_몇_번째_문장인지_보여준다() throws IOException {
        Path sqlFile = writeSql("SELECT * FROM products;\nSELECT * FROM products WHERE price > 1;");

        int exitCode = runCli("--data-dir", dataDir.toString(), sqlFile.toString());

        assertEquals(1, exitCode);
        assertTrue(stderr().contains("SQL_SYNTAX"), stderr());
        assertTrue(stderr().contains("2번째 문장"), stderr());
    }

    @Test
    void 세미콜론이_없는_마지막_문장은_SQL_SYNTAX다() throws IOException {
        Path sqlFile = writeSql("SELECT * FROM products");

        assertEquals(1, runCli("--data-dir", dataDir.toString(), "--json", sqlFile.toString()));
        assertEquals("{\"error\":\"SQL_SYNTAX\"}", stderr().trim());
    }

    @Test
    void 빈_파일_주석뿐인_파일은_아무것도_출력하지_않고_성공한다() throws IOException {
        for (String content : new String[] {"", "   \n", "-- 주석뿐\n", ";;;"}) {
            outBytes.reset();
            Path sqlFile = writeSql(content);

            assertEquals(0, runCli("--data-dir", dataDir.toString(), sqlFile.toString()), content);
            assertEquals("", stdout());
        }
    }

    @Test
    void 없는_파일은_메시지와_종료_코드_1이다() {
        int exitCode = runCli("--data-dir", dataDir.toString(), tempDir.resolve("nothing.sql").toString());

        assertEquals(1, exitCode);
        assertTrue(stderr().contains("SQL 파일이 없습니다"), stderr());
    }

    @Test
    void UTF8이_아닌_파일은_오류다() throws IOException {
        Path sqlFile = tempDir.resolve("bad.sql");
        Files.write(sqlFile, new byte[] {(byte) 0xC3, (byte) 0x28});

        assertEquals(1, runCli("--data-dir", dataDir.toString(), sqlFile.toString()));
        assertTrue(stderr().contains("UTF-8"), stderr());
    }

    @Test
    void BOM이_있는_파일도_읽는다() throws IOException {
        Path sqlFile = tempDir.resolve("bom.sql");
        Files.writeString(sqlFile, "﻿SELECT * FROM products;");

        assertEquals(0, runCli("--data-dir", dataDir.toString(), sqlFile.toString()));
    }

    @Test
    void 인자가_잘못되면_사용법과_종료_코드_1이다() {
        assertEquals(1, runCli());
        assertEquals(1, runCli("--json"));
        assertEquals(1, runCli("--data-dir"));
        assertEquals(1, runCli("--unknown", "a.sql"));
        assertEquals(1, runCli("a.sql", "b.sql"));
        assertTrue(stderr().contains("사용법"), stderr());
    }

    @Test
    void 줄바꿈과_탭이_든_값도_표_한_줄로_그린다() throws IOException {
        Path sqlFile = writeSql("INSERT INTO products (name, price) VALUES ('첫줄\n둘째\t칸', 1);\nSELECT * FROM products;");

        assertEquals(0, runCli("--data-dir", dataDir.toString(), sqlFile.toString()), stderr());

        String[] lines = stdout().trim().replace("\r\n", "\n").split("\n");
        assertEquals(7, lines.length, "inserted 줄 + 표 5줄 + 행 수 줄");
        assertEquals("|  1 | 첫줄\\n둘째\\t칸 |     1 |", lines[4]);
        assertEquals(TableFormatter.displayWidth(lines[1]), TableFormatter.displayWidth(lines[4]), "표 폭이 맞아야 한다");
        assertEquals("1 row", lines[6]);
    }

    @Test
    void 줄바꿈이_든_값도_JSON에서는_이스케이프되어_한_줄이다() throws IOException {
        Path sqlFile = writeSql("INSERT INTO products (name, price) VALUES ('a\nb', 1);\nSELECT name FROM products;");

        assertEquals(0, runCli("--data-dir", dataDir.toString(), "--json", sqlFile.toString()), stderr());

        assertEquals("{\"id\":1}\n{\"rows\":[{\"name\":\"a\\nb\"}]}", stdout().trim().replace("\r\n", "\n"));
    }

    @Test
    void 정렬은_한글_폭을_2로_센다() {
        assertEquals(6, TableFormatter.displayWidth("키보드"));
        assertEquals(4, TableFormatter.displayWidth("ab😀"));
        assertEquals(3, TableFormatter.displayWidth("a가"));
    }

    private Path writeSql(String content) throws IOException {
        Path sqlFile = Files.createTempFile(tempDir, "test", ".sql");
        Files.writeString(sqlFile, content, StandardCharsets.UTF_8);
        return sqlFile;
    }

    private int runCli(String... args) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream errorOutput = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);
        return CliMain.run(args, out, errorOutput);
    }

    private String stdout() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return errorBytes.toString(StandardCharsets.UTF_8);
    }
}
