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
}
