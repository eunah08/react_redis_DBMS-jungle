package minidbms.bench;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidbms.JsonCodec;
import minidbms.sql.SqlParser;
import minidbms.sql.StatementType;

class BenchmarkMainTest {
    @TempDir Path directory;
    private record Output(int status, String text) { }

    private Output run(String... args) {
        var bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            int status = BenchmarkMain.run(args);
            return new Output(status, bytes.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(original); }
    }

    @Test
    void smallSqlBenchmarkMeasuresEqualRowsThroughAllThreePaths() {
        Output output = run("--records", "100", "--repeats", "3", "--random-queries", "5", "--json");
        assertEquals(0, output.status(), output.text());
        Map<?, ?> report = (Map<?, ?>) JsonCodec.parse(output.text());
        assertEquals(100, ((Number) report.get("records")).intValue());
        List<?> measurements = (List<?>) report.get("measurements");
        assertEquals(15, measurements.size());
        for (Object value : measurements) {
            Map<?, ?> row = (Map<?, ?>) value;
            assertTrue(((Number) row.get("meanMs")).doubleValue() >= 0);
            assertEquals(row.get("path").equals("INDEX_ID") ? "INDEX" : "FULL_SCAN", row.get("accessPath"));
        }
    }

    @Test
    void zeroAndOneRecordsAndDirectModeWork() {
        for (String count : List.of("0", "1")) {
            Output output = run("--records", count, "--mode", "direct", "--repeats", "1",
                    "--random-queries", "1", "--json");
            assertEquals(0, output.status(), output.text());
        }
    }

    @Test
    void sqlGeneratorHandlesZeroAndOneWithoutOverwritingFiles() throws Exception {
        Path empty = directory.resolve("zero.sql");
        assertEquals(0, run("--records", "0", "--generate-sql", empty.toString()).status());
        assertEquals("", Files.readString(empty));
        Path one = directory.resolve("one.sql");
        assertEquals(0, run("--records", "1", "--generate-sql", one.toString()).status());
        assertEquals(StatementType.INSERT, SqlParser.parseSql(Files.readString(one)).type());
        assertEquals(1, run("--records", "1", "--generate-sql", one.toString()).status());
    }
}
