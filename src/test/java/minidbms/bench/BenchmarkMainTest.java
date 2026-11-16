package minidbms.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import minidbms.cli.CliMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 12장, 13장 Phase 2 : 벤치마크 실행기와 SQL 생성기(0건, 1건 포함).
 */
class BenchmarkMainTest {

    @TempDir
    Path tempDir;

    @Test
    void 작은_건수로_적재와_측정을_끝까지_실행한다() {
        for (String loadMode : new String[] {"sql", "direct"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int exitCode = BenchmarkMain.run(new String[] {"--records", "500", "--steps", "100,500", "--load", loadMode,
                    "--repeat", "3", "--warmup", "1", "--random-count", "20", "--linear-random-count", "5",
                    "--data-dir", tempDir.resolve("bench-" + loadMode).toString()},
                    new PrintStream(bytes, true, StandardCharsets.UTF_8));

            String report = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode, report);
            assertTrue(report.contains("### 100건"), report);
            assertTrue(report.contains("### 500건"), report);
            assertTrue(report.contains("`WHERE id = ?` (인덱스)"), report);
            assertTrue(report.contains("`WHERE name = ?` (선형)"), report);
            assertTrue(report.contains("재시작 뒤 인덱스 재구성"), report);
            assertTrue(report.contains("id=501"), "없는 id도 측정한다");
        }
    }

    @Test
    void 이미_데이터가_있는_폴더에는_적재하지_않는다() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("used"));
        Files.writeString(dataDir.resolve("products.tbl"), "x");

        int exitCode = BenchmarkMain.run(new String[] {"--records", "10", "--data-dir", dataDir.toString()},
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, exitCode);
    }

    @Test
    void 건수_0과_1에서도_끝까지_실행된다() {
        for (String records : new String[] {"0", "1"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int exitCode = BenchmarkMain.run(new String[] {"--records", records, "--repeat", "2", "--warmup", "0"},
                    new PrintStream(bytes, true, StandardCharsets.UTF_8));

            assertEquals(0, exitCode, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 잘못된_옵션은_종료_코드_1이다() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(1, BenchmarkMain.run(new String[] {"--records"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--records", "abc"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--records", "-1"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--nothing", "1"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--load", "x"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--records", "10", "--steps", "20"}, out));
        assertEquals(1, BenchmarkMain.run(new String[] {"--repeat", "0"}, out));
    }

    @Test
    void SQL_파일_생성은_0건이면_빈_파일_1건이면_한_줄이다() throws IOException {
        Path empty = tempDir.resolve("zero.sql");
        Path one = tempDir.resolve("one.sql");
        PrintStream out = new PrintStream(new ByteArrayOutputStream());

        assertEquals(0, BenchmarkMain.run(new String[] {"--records", "0", "--generate-sql", empty.toString()}, out));
        assertEquals(0, BenchmarkMain.run(new String[] {"--records", "1", "--generate-sql", one.toString()}, out));

        assertEquals("", Files.readString(empty));
        assertEquals(List.of("INSERT INTO products (name, price) VALUES ('product_0000001', 1);"),
                Files.readAllLines(one));
    }

    @Test
    void 만든_SQL_파일을_CLI로_그대로_넣을_수_있다() throws IOException {
        Path sqlFile = tempDir.resolve("gen.sql");
        Path dataDir = Files.createDirectories(tempDir.resolve("cli-data"));
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        BenchmarkMain.run(new String[] {"--records", "50", "--generate-sql", sqlFile.toString()},
                new PrintStream(new ByteArrayOutputStream()));
        Path checkFile = tempDir.resolve("check.sql");
        Files.writeString(checkFile, "SELECT * FROM products WHERE id = 50;");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertEquals(0, CliMain.run(new String[] {"--data-dir", dataDir.toString(), sqlFile.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        assertEquals(0, CliMain.run(new String[] {"--data-dir", dataDir.toString(), "--json", checkFile.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream())));

        assertEquals("{\"rows\":[{\"id\":50,\"name\":\"product_0000050\",\"price\":50}]}",
                out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void 처리량_모드는_워커_수_1_2_4_8의_표를_출력한다() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int exitCode = BenchmarkMain.run(new String[] {"--mode", "throughput", "--records", "200", "--requests", "40",
                "--clients", "4", "--data-dir", tempDir.resolve("throughput").toString()},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String report = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, report);
        for (String workers : new String[] {"| 1 |", "| 2 |", "| 4 |", "| 8 |"}) {
            assertTrue(report.contains(workers), report);
        }
        assertTrue(report.contains("워커 수별 처리량"), report);
    }

    @Test
    void 통계는_중앙값_평균_p95를_ms로_돌려준다() {
        QueryStats stats = QueryStats.summarize(List.of(5_000_000L, 1_000_000L, 3_000_000L, 2_000_000L, 4_000_000L));

        assertEquals(3.0, stats.medianMs(), 1e-9);
        assertEquals(3.0, stats.meanMs(), 1e-9);
        assertEquals(5.0, stats.p95Ms(), 1e-9);
        assertEquals(2.5, QueryStats.summarize(List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L)).medianMs(), 1e-9);
    }
}
