package minidbms.bench;

import minidbms.engine.AccessPath;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 건수 하나에 대해 데이터를 만들고 조회 시간을 잰다. (협약 12장)
 * 순서 : 적재 → 재시작(인덱스 다시 만들기) → 예열 → 인덱스·선형 탐색을 번갈아 repeat번 측정 → 무작위 id 조회.
 * 적재 시간과 인덱스 재구성 시간은 조회 측정에서 빼고 따로 기록한다.
 */
final class BenchmarkRunner {

    private static final int WARMUP_ROUNDS = 2;
    private static final int LINEAR_RANDOM_LIMIT = 100;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final BenchmarkOptions options;
    private final int records;
    private final Path dataDir;
    private final PrintStream out;

    /**
     * @param options 옵션
     * @param records 이번에 만들 건수
     * @param dataDir 빈 데이터 폴더
     * @param out     결과를 쓸 곳
     */
    BenchmarkRunner(BenchmarkOptions options, int records, Path dataDir, PrintStream out) {
        this.options = options;
        this.records = records;
        this.dataDir = dataDir;
        this.out = out;
    }

    /**
     * 적재하고 측정해서 표를 출력한다.
     *
     * @throws IOException 파일을 만들거나 읽지 못했을 때
     */
    void run() throws IOException {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n", StandardCharsets.UTF_8);
        long loadMs = loadData();
        long rebuildStart = System.nanoTime();
        DbEngine engine = new DbEngine(dataDir);
        long rebuildMs = (long) ((System.nanoTime() - rebuildStart) / NANOS_PER_MILLI);
        try {
            out.printf("%n## %,d건 (적재 %,d ms [%s], 시작 시 인덱스 재구성 %,d ms)%n", records, loadMs,
                    options.isDirectLoad() ? "엔진 직접 삽입" : "SQL 처리기 INSERT", rebuildMs);
            measureLookups(engine);
        } finally {
            engine.close();
        }
    }

    private long loadData() {
        DbEngine engine = new DbEngine(dataDir);
        long start = System.nanoTime();
        try {
            for (int i = 1; i <= records; i++) {
                insertOne(engine, i);
            }
        } finally {
            engine.close();
        }
        return (long) ((System.nanoTime() - start) / NANOS_PER_MILLI);
    }

    private void insertOne(DbEngine engine, int number) {
        DbResult result;
        if (options.isDirectLoad()) {
            result = engine.executeStatement(new Statement(StatementType.INSERT, "products",
                    List.of("name", "price"), List.of(productName(number), (long) number), null));
        } else {
            result = engine.execute(SqlFileGenerator.insertSql(number));
        }
        if (result.isError()) {
            throw new IllegalStateException("[insertOne] 적재 실패: " + result.getErrorMessage());
        }
    }

    private void measureLookups(DbEngine engine) {
        List<Integer> ids = List.of(1, Math.max(records / 2, 1), records, records + 1);
        LatencyStats indexStats = new LatencyStats();
        LatencyStats linearStats = new LatencyStats();
        LatencyStats scanStats = new LatencyStats();
        for (int round = -WARMUP_ROUNDS; round < options.repeat(); round++) {
            for (int id : ids) {
                measureOneId(engine, id, round < 0 ? null : new LatencyStats[] {indexStats, linearStats, scanStats});
            }
        }
        printTable(indexStats, linearStats, scanStats);
        printRandomLookups(engine);
    }

    /** id 하나를 세 방식으로 번갈아 조회한다. stats가 null이면 예열이라 기록하지 않는다. */
    private void measureOneId(DbEngine engine, int id, LatencyStats[] stats) {
        String idSql = "SELECT * FROM products WHERE id = " + id + ";";
        String nameSql = "SELECT * FROM products WHERE name = '" + productName(id) + "';";
        long[] elapsed = new long[3];
        DbResult byIndex = timed(() -> engine.execute(idSql), elapsed, 0);
        DbResult byName = timed(() -> engine.execute(nameSql), elapsed, 1);
        DbResult byScan = timed(() -> engine.executeFullScan(idSql), elapsed, 2);
        checkSameResult(byIndex, byName, byScan);
        if (stats != null) {
            for (int i = 0; i < stats.length; i++) {
                stats[i].add(elapsed[i]);
            }
        }
    }

    private static DbResult timed(Supplier<DbResult> query, long[] elapsed, int slot) {
        long start = System.nanoTime();
        DbResult result = query.get();
        elapsed[slot] = System.nanoTime() - start;
        return result;
    }

    /** 세 경로의 결과가 같고 접근 방식이 기대와 같은지 확인한다. (협약 12장 : 공통 통과 기준) */
    private static void checkSameResult(DbResult byIndex, DbResult byName, DbResult byScan) {
        boolean isSame = !byIndex.isError() && byIndex.getRows().equals(byName.getRows())
                && byIndex.getRows().equals(byScan.getRows());
        boolean isPathOk = byIndex.getAccessPath() == AccessPath.INDEX
                && byName.getAccessPath() == AccessPath.FULL_SCAN
                && byScan.getAccessPath() == AccessPath.FULL_SCAN;
        if (!isSame || !isPathOk) {
            throw new IllegalStateException("[checkSameResult] 조회 경로마다 결과 또는 접근 방식이 다릅니다");
        }
    }

    private void printTable(LatencyStats indexStats, LatencyStats linearStats, LatencyStats scanStats) {
        out.println("| 레코드 수 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수(중앙값) |");
        out.println("|---|---|---|---|---|---|");
        printRow("`WHERE id = ?` (인덱스)", indexStats, indexStats);
        printRow("`WHERE name = ?` (선형)", linearStats, indexStats);
        printRow("`WHERE id = ?` (강제 파일 스캔)", scanStats, indexStats);
        out.printf("측정 : id %d개(첫·중간·마지막·없는 id) x %d회 번갈아 측정, 예열 %d회 제외, 표본 %d개%n",
                4, options.repeat(), WARMUP_ROUNDS, indexStats.count());
    }

    private void printRow(String label, LatencyStats stats, LatencyStats baseline) {
        double ratio = stats.medianMs() / baseline.medianMs();
        out.printf("| %,d | %s | %.4f | %.4f | %.4f | %.1fx |%n", records, label,
                stats.meanMs(), stats.medianMs(), stats.p95Ms(), ratio);
    }

    private void printRandomLookups(DbEngine engine) {
        Random random = new Random(options.seed());
        LatencyStats indexStats = new LatencyStats();
        LatencyStats scanStats = new LatencyStats();
        int scanCount = Math.min(options.randomCount(), LINEAR_RANDOM_LIMIT);
        for (int i = 0; i < options.randomCount(); i++) {
            String idSql = "SELECT * FROM products WHERE id = " + (random.nextInt(records) + 1) + ";";
            indexStats.add(timeOnce(() -> engine.execute(idSql)));
            if (i < scanCount) {
                scanStats.add(timeOnce(() -> engine.executeFullScan(idSql)));
            }
        }
        out.printf("무작위 id (시드 %d) 평균 : 인덱스 %.4f ms (%d회), 강제 파일 스캔 %.4f ms (%d회)%n",
                options.seed(), indexStats.meanMs(), indexStats.count(), scanStats.meanMs(), scanStats.count());
    }

    private static long timeOnce(Supplier<DbResult> query) {
        long start = System.nanoTime();
        query.get();
        return System.nanoTime() - start;
    }

    /**
     * @param number 일련번호
     * @return product_0000001 형식의 이름
     */
    static String productName(int number) {
        return String.format("product_%07d", number);
    }

    /**
     * @return 이 러너가 쓰는 데이터 폴더 안의 파일 목록 (크기 기록용)
     * @throws IOException 읽지 못했을 때
     */
    List<String> describeFiles() throws IOException {
        List<String> lines = new ArrayList<>();
        try (var files = Files.list(dataDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                lines.add(file.getFileName() + " " + Files.size(file) + " bytes");
            }
        }
        return lines;
    }
}
