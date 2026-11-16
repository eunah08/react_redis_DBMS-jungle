package minidbms.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 9.1, 10.1, 11.1 : cli 실행 결과와 실패 정책 검증.
 */
class CliMainTest {

    private record Outcome(int exitCode, String out, String err) {
    }

    private static Outcome run(Path dataDir, Path sqlFile, boolean isJson) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        String[] args = isJson
                ? new String[] {"--data-dir", dataDir.toString(), "--json", sqlFile.toString()}
                : new String[] {"--data-dir", dataDir.toString(), sqlFile.toString()};
        int exitCode = CliMain.run(args, outStream, errStream);
        outStream.flush();
        return new Outcome(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path prepare(Path dir, String sql) throws IOException {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        Path sqlFile = dir.resolve("test.sql");
        Files.writeString(sqlFile, sql);
        return sqlFile;
    }

    private static List<String> lines(String text) {
        return text.lines().toList();
    }

    @Test
    void 공통_fixture의_JSON_출력이_기대_출력과_같다(@TempDir Path dir) throws IOException {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        Path sqlFile = Path.of("src/test/resources/fixtures/basic.sql");
        Path expectedFile = Path.of("src/test/resources/fixtures/basic.expected.jsonl");

        Outcome outcome = run(dataDir, sqlFile, true);

        assertEquals(0, outcome.exitCode());
        assertEquals(lines(Files.readString(expectedFile, StandardCharsets.UTF_8)), lines(outcome.out()));
        assertEquals("", outcome.err());
    }

    @Test
    void 표_출력은_행_수를_보여준다(@TempDir Path dir) throws IOException {
        Path sqlFile = prepare(dir, "INSERT INTO products (name, price) VALUES ('키보드', 1);\nSELECT * FROM products;\n");

        Outcome outcome = run(dir.resolve("data"), sqlFile, false);

        assertEquals(0, outcome.exitCode());
        assertTrue(outcome.out().contains("키보드"));
        assertTrue(outcome.out().contains("1 row"));
    }

    @Test
    void 실패한_문장에서_멈추고_앞의_INSERT는_남는다(@TempDir Path dir) throws IOException {
        Path sqlFile = prepare(dir, "INSERT INTO products (name, price) VALUES ('a', 1);\n"
                + "SELECT * FROM nothing;\n"
                + "INSERT INTO products (name, price) VALUES ('b', 2);\n");

        Outcome outcome = run(dir.resolve("data"), sqlFile, true);

        assertEquals(1, outcome.exitCode());
        assertEquals(List.of("{\"id\":1}"), lines(outcome.out()));
        assertTrue(outcome.err().contains("UNKNOWN_TABLE"));
        Path checkFile = dir.resolve("check.sql");
        Files.writeString(checkFile, "SELECT * FROM products;");
        Outcome check = run(dir.resolve("data"), checkFile, true);
        assertEquals(1, lines(check.out()).size());
        assertTrue(check.out().contains("\"name\":\"a\""));
    }

    @Test
    void 세미콜론이_없는_마지막_문장은_구문_오류다(@TempDir Path dir) throws IOException {
        Path sqlFile = prepare(dir, "SELECT * FROM products");

        Outcome outcome = run(dir.resolve("data"), sqlFile, true);

        assertEquals(1, outcome.exitCode());
        assertTrue(outcome.err().contains("SQL_SYNTAX"));
    }

    @Test
    void 빈_파일과_주석뿐인_파일과_세미콜론뿐인_파일은_아무것도_하지_않고_성공한다(@TempDir Path dir) throws IOException {
        for (String text : List.of("", "  \n\n", "-- 주석만\n", ";;;\n")) {
            Path sqlFile = prepare(dir, text);

            Outcome outcome = run(dir.resolve("data"), sqlFile, true);

            assertEquals(0, outcome.exitCode(), "내용: " + text);
            assertEquals("", outcome.out());
            assertEquals("", outcome.err());
        }
    }

    @Test
    void 없는_파일과_잘못된_인자는_종료_코드_1이다(@TempDir Path dir) throws IOException {
        prepare(dir, "");
        Path dataDir = dir.resolve("data");

        Outcome missing = run(dataDir, dir.resolve("missing.sql"), false);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int noArgs = CliMain.run(new String[] {}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        int unknownOption = CliMain.run(new String[] {"--nope", "x"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, missing.exitCode());
        assertFalse(missing.err().isEmpty());
        assertEquals(1, noArgs);
        assertEquals(1, unknownOption);
    }

    @Test
    void CRLF_줄바꿈과_UTF8_BOM_파일도_읽는다(@TempDir Path dir) throws IOException {
        Path sqlFile = prepare(dir, "");
        Files.writeString(sqlFile, "﻿INSERT INTO products (name, price) VALUES ('가', 1);\r\nSELECT * FROM products;\r\n");

        Outcome outcome = run(dir.resolve("data"), sqlFile, true);

        assertEquals(0, outcome.exitCode(), outcome.err());
        assertEquals(2, lines(outcome.out()).size());
    }
}
