package minidbms.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import minidbms.engine.BulkLoader;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;

/** 같은 products 데이터를 SQL/파싱 생략 두 경로로 만들고 SQL 파일도 생성한다. */
final class BenchmarkData {
    private static final List<String> COLUMNS = List.of("name", "price");
    private static final int NAME_DIGITS = 7;
    private static final int PROGRESS_INTERVAL = 100000;
    private BenchmarkData() { }

    static Path prepare(BenchmarkOptions options) throws IOException {
        Path dataDir = options.dataDir == null ? Files.createTempDirectory("mini-dbms-bench-") : options.dataDir;
        Files.createDirectories(dataDir);
        try (var paths = Files.list(dataDir)) {
            if (paths.findAny().isPresent()) { throw new IOException("비어 있는 데이터 폴더가 필요합니다"); }
        }
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n",
                StandardOpenOption.CREATE_NEW);
        return dataDir;
    }

    static String productName(int id) {
        String digits = Integer.toString(id);
        return "product_" + "0".repeat(Math.max(0, NAME_DIGITS - digits.length())) + digits;
    }

    static String insertSql(int id) {
        return "INSERT INTO products (name, price) VALUES ('" + productName(id) + "', " + id + ");";
    }

    static long load(DbEngine engine, BenchmarkOptions options) {
        long started = System.nanoTime();
        for (int index = 0; index < options.records; index++) {
            int id = index + 1;
            DbResult result = options.isDirect
                    ? BulkLoader.execute(engine, new Statement(StatementType.INSERT, "products", COLUMNS,
                            List.of(productName(id), id), null)) : engine.execute(insertSql(id));
            if (!result.isSuccess() || result.getId() != id) {
                throw new IllegalStateException("[loadData] 삽입 결과가 다릅니다");
            }
            if (id % PROGRESS_INTERVAL == 0) { System.err.println("[loadData] " + id + "/" + options.records); }
        }
        return System.nanoTime() - started;
    }

    static void generateSql(BenchmarkOptions options) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(options.sqlOutput, StandardOpenOption.CREATE_NEW)) {
            for (int index = 0; index < options.records; index++) {
                writer.write(insertSql(index + 1)); writer.newLine();
            }
        }
    }
}
