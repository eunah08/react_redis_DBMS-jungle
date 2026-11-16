package minidbms.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliMainTest {
    @TempDir Path dataDir;
    private record Output(int status, String stdout, String stderr) { }

    private Output runFile(Path sqlFile) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/classes").toAbsolutePath().toString(), "minidbms.Main", "cli",
                "--data-dir", dataDir.toString(), "--json", sqlFile.toString()));
        Process process = new ProcessBuilder(command).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return new Output(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void schema() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
    }

    @Test
    void commonFixtureMatchesAndAnotherProcessReadsPersistentRows() throws Exception {
        schema();
        Output output = runFile(Path.of("src/test/resources/fixtures/basic.sql"));
        assertEquals(0, output.status(), output.stderr());
        assertEquals(Files.readString(Path.of("src/test/resources/fixtures/basic.expected.jsonl")), output.stdout());
        Path query = dataDir.resolve("query.sql");
        Files.writeString(query, "SELECT * FROM products WHERE id = 1;");
        Output reopened = runFile(query);
        assertEquals(0, reopened.status(), reopened.stderr());
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\",\"price\":35000}]}\n", reopened.stdout());
    }

    @Test
    void stopsOnBadTailButKeepsEarlierInsertAcrossProcesses() throws Exception {
        schema();
        Path input = dataDir.resolve("input.sql");
        Files.writeString(input, "INSERT INTO products (name, price) VALUES ('오''늘;😀', 7);\n"
                + "SELECT unknown FROM products;\nINSERT INTO products (name, price) VALUES ('later', 8);");
        Output failed = runFile(input);
        assertEquals(1, failed.status());
        assertEquals("{\"id\":1}\n", failed.stdout());
        assertEquals("{\"error\":\"UNKNOWN_COLUMN\"}\n", failed.stderr());
        Files.writeString(input, "SELECT * FROM products; INSERT INTO products (name, price) VALUES ('next', 1);");
        Output reopened = runFile(input);
        assertEquals(0, reopened.status(), reopened.stderr());
        assertTrue(reopened.stdout().contains("오'늘;😀"));
        assertTrue(reopened.stdout().endsWith("{\"id\":2}\n"));
    }

    @Test
    void emptyCommentOnlyAndMissingFileHaveDocumentedResults() throws Exception {
        schema();
        Path input = dataDir.resolve("input.sql");
        Files.writeString(input, " -- comment ;\n");
        assertEquals(new Output(0, "", ""), runFile(input));
        Files.writeString(input, ";;");
        assertEquals("{\"error\":\"SQL_SYNTAX\"}\n", runFile(input).stderr());
        assertEquals("{\"error\":\"INTERNAL_ERROR\"}\n", runFile(dataDir.resolve("missing.sql")).stderr());
    }
}
