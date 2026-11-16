package minidbms.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import minidbms.engine.AccessPath;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;

/**
 * 100만 건 적재와 조회 성능 측정을 한 번 실행한다. (협약 12장)
 *
 * <p>같은 프로세스·데이터·빌드에서 인덱스 경로와 선형 경로를 예열한 뒤 번갈아 측정한다.
 * 측정 구간은 엔진 호출부터 결과 객체가 준비될 때까지다. 적재 시간, 시작할 때 인덱스를 다시 만드는 시간,
 * JSON 변환·출력 시간은 조회 측정에서 빼고 따로 적는다. 같은 행을 돌려주는지와 접근 경로를 매번 검사한다.
 */
final class BenchmarkRunner {

    private static final String TABLE_NAME = "products";
    private static final List<String> INSERT_COLUMNS = List.of("name", "price");
    private static final int PROGRESS_INTERVAL = 100_000;

    private final BenchmarkOptions options;
    private final Path dataDir;
    private final PrintStream out;
    private DbEngine engine;

    BenchmarkRunner(BenchmarkOptions options, Path dataDir, PrintStream out) {
        this.options = options;
        this.dataDir = dataDir;
        this.out = out;
    }

    /**
     * 적재와 측정을 모두 실행하고 보고서를 출력한다.
     *
     * @return 성공하면 0
     * @throws IOException 파일 처리 실패
     */
    int run() throws IOException {
        prepareDataDir();
        List<String> report = new ArrayList<>();
        engine = new DbEngine(dataDir);
        try {
            int loaded = 0;
            for (int step : options.steps()) {
                double loadSeconds = loadRows(loaded + 1, step);
                loaded = step;
                report.addAll(measureStep(step, loadSeconds));
            }
        } finally {
            engine.close();
        }
        report.addAll(measureRestart(options.records()));
        printReport(report);
        return 0;
    }

    /**
     * 적재할 행의 이름을 만든다. product_0000001 처럼 7자리 일련번호다. (협약 12장)
     *
     * @param serial 일련번호 (1부터)
     * @return 이름
     */
    static String productName(int serial) {
        return String.format(Locale.ROOT, "product_%07d", serial);
    }

    private void prepareDataDir() throws IOException {
        Files.createDirectories(dataDir);
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(dataDir, "*.tbl")) {
            if (existing.iterator().hasNext()) {
                throw new IllegalArgumentException("[prepareDataDir] 데이터 폴더가 비어 있어야 합니다: " + dataDir);
            }
        }
        Files.writeString(dataDir.resolve(TABLE_NAME + ".schema"), "name string 32\nprice int\n");
    }

    private double loadRows(int from, int to) {
        long start = System.nanoTime();
        for (int serial = from; serial <= to; serial++) {
            DbResult result = options.loadMode() == BenchmarkOptions.LoadMode.SQL
                    ? engine.execute(insertSql(serial))
                    : engine.execute(insertStatement(serial), true);
            if (result.isError()) {
                throw new IllegalStateException("[loadRows] 적재 실패 (" + serial + "번째): " + result.getMessage());
            }
            if (serial % PROGRESS_INTERVAL == 0) {
                out.println("  적재 " + format(serial) + " / " + format(options.records()));
            }
        }
        return (System.nanoTime() - start) / 1_000_000_000.0;
    }

    private static String insertSql(int serial) {
        return "INSERT INTO products (name, price) VALUES ('" + productName(serial) + "', " + serial + ");";
    }

    private static Statement insertStatement(int serial) {
        return new Statement(StatementType.INSERT, TABLE_NAME, INSERT_COLUMNS,
                List.of(productName(serial), (long) serial), null);
    }

    private List<String> measureStep(int recordCount, double loadSeconds) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("### " + format(recordCount) + "건 (적재 " + String.format(Locale.ROOT, "%.2f", loadSeconds)
                + "초, 트리 높이 " + engine.getIndexHeight(TABLE_NAME) + ")");
        lines.add("");
        lines.add("| 대상 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |");
        lines.add("|---|---|---|---|---|---|");
        for (int targetId : targetIds(recordCount)) {
            lines.addAll(measureTarget(targetId));
        }
        lines.addAll(measureRandom(recordCount));
        return lines;
    }

    private static List<Integer> targetIds(int recordCount) {
        List<Integer> ids = new ArrayList<>();
        if (recordCount >= 1) {
            ids.add(1);
        }
        if (recordCount >= 3) {
            ids.add((recordCount + 1) / 2);
        }
        if (recordCount >= 2) {
            ids.add(recordCount);
        }
        ids.add(recordCount + 1);
        return ids;
    }

    private List<String> measureTarget(int targetId) {
        String idSql = "SELECT * FROM products WHERE id = " + targetId + ";";
        String nameSql = "SELECT * FROM products WHERE name = '" + productName(targetId) + "';";
        List<Query> queries = List.of(
                new Query("`WHERE id = ?` (인덱스)", idSql, true, AccessPath.INDEX),
                new Query("`WHERE id = ?` (강제 선형)", idSql, false, AccessPath.FULL_SCAN),
                new Query("`WHERE name = ?` (선형)", nameSql, true, AccessPath.FULL_SCAN));
        List<List<Long>> timings = measureAlternating(queries);
        return toRows("id=" + format(targetId), queries, timings);
    }

    /** 예열한 뒤 질의를 번갈아 repeat번 측정한다. 모든 질의가 같은 행을 돌려주는지 검사한다. */
    private List<List<Long>> measureAlternating(List<Query> queries) {
        for (int i = 0; i < options.warmup(); i++) {
            for (Query query : queries) {
                run(query);
            }
        }
        List<List<Long>> timings = new ArrayList<>();
        queries.forEach(query -> timings.add(new ArrayList<>()));
        List<List<Object>> expectedRows = null;
        for (int round = 0; round < options.repeat(); round++) {
            for (int i = 0; i < queries.size(); i++) {
                long start = System.nanoTime();
                DbResult result = run(queries.get(i));
                timings.get(i).add(System.nanoTime() - start);
                expectedRows = checkResult(queries.get(i), result, expectedRows);
            }
        }
        return timings;
    }

    private DbResult run(Query query) {
        return engine.execute(query.sql(), query.isIndexAllowed());
    }

    private static List<List<Object>> checkResult(Query query, DbResult result, List<List<Object>> expectedRows) {
        if (result.isError() || result.getAccessPath() != query.expectedPath()) {
            throw new IllegalStateException("[checkResult] 접근 경로가 다릅니다: " + query.label()
                    + " -> " + result.getAccessPath() + (result.isError() ? " " + result.getMessage() : ""));
        }
        if (expectedRows != null && !expectedRows.equals(result.getRows())) {
            throw new IllegalStateException("[checkResult] 같은 행을 돌려주지 않습니다: " + query.label());
        }
        return result.getRows();
    }

    private List<String> measureRandom(int recordCount) {
        List<String> lines = new ArrayList<>();
        if (recordCount < 1) {
            return lines;
        }
        Random random = new Random(options.seed() ^ recordCount);
        List<Long> indexTimes = new ArrayList<>();
        List<Long> linearTimes = new ArrayList<>();
        for (int i = 0; i < Math.max(options.randomCount(), options.linearRandomCount()); i++) {
            int id = random.nextInt(recordCount) + 1;
            Query index = new Query("무작위 id (인덱스)", "SELECT * FROM products WHERE id = " + id + ";",
                    true, AccessPath.INDEX);
            Query linear = new Query("무작위 id (선형)",
                    "SELECT * FROM products WHERE name = '" + productName(id) + "';", true, AccessPath.FULL_SCAN);
            measureRandomPair(i, index, linear, indexTimes, linearTimes);
        }
        addRandomRows(lines, indexTimes, linearTimes);
        return lines;
    }

    private void measureRandomPair(int round, Query index, Query linear, List<Long> indexTimes, List<Long> linearTimes) {
        List<List<Object>> expected = null;
        if (round < options.randomCount()) {
            long start = System.nanoTime();
            DbResult result = run(index);
            indexTimes.add(System.nanoTime() - start);
            expected = checkResult(index, result, null);
        }
        if (round < options.linearRandomCount()) {
            long start = System.nanoTime();
            DbResult result = run(linear);
            linearTimes.add(System.nanoTime() - start);
            checkResult(linear, result, expected);
        }
    }

    private void addRandomRows(List<String> lines, List<Long> indexTimes, List<Long> linearTimes) {
        QueryStats indexStats = indexTimes.isEmpty() ? null : QueryStats.summarize(indexTimes);
        if (indexStats != null) {
            lines.add(row("무작위 id " + indexTimes.size() + "개", "인덱스", indexStats, 1.0));
        }
        if (!linearTimes.isEmpty()) {
            QueryStats linearStats = QueryStats.summarize(linearTimes);
            double ratio = indexStats == null ? Double.NaN : linearStats.medianMs() / indexStats.medianMs();
            lines.add(row("무작위 id " + linearTimes.size() + "개", "`WHERE name = ?` (선형)", linearStats, ratio));
        }
    }

    private List<String> toRows(String target, List<Query> queries, List<List<Long>> timings) {
        List<String> lines = new ArrayList<>();
        QueryStats base = QueryStats.summarize(timings.get(0));
        for (int i = 0; i < queries.size(); i++) {
            QueryStats stats = QueryStats.summarize(timings.get(i));
            lines.add(row(target, queries.get(i).label(), stats, stats.medianMs() / base.medianMs()));
        }
        return lines;
    }

    private static String row(String target, String method, QueryStats stats, double ratio) {
        String ratioText = Double.isNaN(ratio) ? "-" : String.format(Locale.ROOT, "%.1fx", ratio);
        return String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f | %.4f | %s |",
                target, method, stats.meanMs(), stats.medianMs(), stats.p95Ms(), ratioText);
    }

    /** 엔진을 다시 열어 인덱스를 다시 만드는 시간을 재고, 재시작 전후 결과가 같은지 본다. */
    private List<String> measureRestart(int recordCount) {
        long start = System.nanoTime();
        DbEngine reopened = new DbEngine(dataDir);
        double rebuildSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        try {
            if (reopened.getIndexSize(TABLE_NAME) != recordCount) {
                throw new IllegalStateException("[measureRestart] 재시작 뒤 인덱스 크기가 다릅니다");
            }
            verifyAfterRestart(reopened, recordCount);
        } finally {
            reopened.close();
        }
        long fileBytes = fileSize(dataDir.resolve(TABLE_NAME + ".tbl"));
        return List.of("", String.format(Locale.ROOT,
                "- 재시작 뒤 인덱스 재구성: %.2f초 (%s건). 재시작 전후 조회 결과 일치 확인",
                rebuildSeconds, format(recordCount)),
                "- 테이블 파일: " + format(fileBytes) + " 바이트"
                        + (recordCount > 0 ? " (행당 평균 " + fileBytes / recordCount + " 바이트)" : ""));
    }

    private void verifyAfterRestart(DbEngine reopened, int recordCount) {
        if (recordCount < 1) {
            return;
        }
        String sql = "SELECT * FROM products WHERE id = " + recordCount + ";";
        DbResult result = reopened.execute(sql);
        List<Object> expectedRow = List.of(recordCount, productName(recordCount), recordCount);
        if (result.isError() || !result.getRows().equals(List.of(expectedRow))) {
            throw new IllegalStateException("[verifyAfterRestart] 재시작 뒤 마지막 행이 다릅니다");
        }
    }

    private void printReport(List<String> report) {
        out.println();
        out.println("## 벤치마크 결과");
        out.println();
        out.println("- 실행 환경: JDK " + Runtime.version() + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", CPU 코어 " + Runtime.getRuntime().availableProcessors()
                + "개, 최대 힙 " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "MB");
        out.println("- 데이터: " + format(options.records()) + "건, 적재 방법 " + options.loadMode()
                + ", 레코드 products(id int, name ≤32바이트, price int), 시드 " + options.seed());
        out.println("- 측정: 예열 " + options.warmup() + "회 뒤 번갈아 " + options.repeat()
                + "회, 무작위 id 인덱스 " + options.randomCount() + "개·선형 " + options.linearRandomCount() + "개");
        out.println("- 배수는 같은 대상의 인덱스 중앙값 대비 중앙값 비율이다");
        report.forEach(out::println);
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("[fileSize] 파일 크기를 읽지 못했습니다: " + path, e);
        }
    }

    private static String format(long number) {
        return String.format(Locale.ROOT, "%,d", number);
    }

    private record Query(String label, String sql, boolean isIndexAllowed, AccessPath expectedPath) {
    }
}
