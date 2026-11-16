package minidbms.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 11.2 : 벤치마크 옵션 해석, SQL 파일 생성, 작은 건수 실행 검증.
 */
class BenchmarkTest {

    @Test
    void 옵션_기본값과_쉼표_목록을_읽는다() {
        assertEquals(List.of(1_000_000), BenchmarkOptions.parse(new String[] {}).recordSteps());
        assertEquals(List.of(10, 100), BenchmarkOptions.parse(new String[] {"--records", "10,100"}).recordSteps());
    }

    @Test
    void 잘못된_옵션은_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--records"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--records", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--records", "-5"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--load-mode", "x"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--repeat", "0"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--nope", "1"}));
    }

    @Test
    void SQL_파일은_한_줄에_INSERT_하나씩이다(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gen.sql");

        SqlFileGenerator.write(file, 3);

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertEquals(SqlFileGenerator.insertSql(1), lines.get(0));
        assertTrue(lines.get(2).endsWith(", 3);"));
    }

    @Test
    void 작은_건수로_벤치마크가_끝까지_돈다(@TempDir Path dir) {
        int exitCode = BenchmarkMain.run(new String[] {"--records", "200", "--repeat", "2", "--random-count", "20",
                "--data-dir", dir.resolve("bench-data").toString()});

        assertEquals(0, exitCode);
    }

    @Test
    void 직접_적재_모드도_끝까지_돈다(@TempDir Path dir) {
        int exitCode = BenchmarkMain.run(new String[] {"--records", "200", "--repeat", "2", "--random-count", "20",
                "--load-mode", "direct", "--data-dir", dir.resolve("bench-data").toString()});

        assertEquals(0, exitCode);
    }

    @Test
    void generate_sql은_파일만_만들고_끝난다(@TempDir Path dir) {
        Path file = dir.resolve("out.sql");

        int exitCode = BenchmarkMain.run(new String[] {"--records", "5", "--generate-sql", file.toString()});

        assertEquals(0, exitCode);
        assertTrue(Files.exists(file));
    }

    @Test
    void 처리량_옵션을_읽고_잘못된_값은_거부한다() {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[] {"--mode", "throughput", "--requests", "50", "--clients", "4"});

        assertTrue(options.isThroughput());
        assertEquals(50, options.requests());
        assertEquals(4, options.clients());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--mode", "fast"}));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkOptions.parse(new String[] {"--clients", "0"}));
    }

    @Test
    void 처리량_측정이_끝까지_돈다(@TempDir Path dir) {
        int exitCode = BenchmarkMain.run(new String[] {"--mode", "throughput", "--records", "300", "--requests", "40",
                "--clients", "4", "--data-dir", dir.resolve("tp-data").toString()});

        assertEquals(0, exitCode);
    }
}
