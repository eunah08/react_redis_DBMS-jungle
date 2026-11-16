package minidbms.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import minidbms.JsonCodec;
import minidbms.engine.AccessPath;
import minidbms.engine.DbEngine;

/** 협약 12장의 데이터 생성·공통 SQL 적재·정확성 확인·조회 시간 측정 명령이다. */
public final class BenchmarkMain {
    private static final double NANOSECONDS_PER_MS = 1000000.0;
    private BenchmarkMain() { }

    /**
     * bench 명령을 실행한다. 기본은 SQL 경유 100건, 예열 2회 뒤 1회·무작위 3개다. 사용자 지시로 최소화했다.
     * @param args --records/--repeats/--random-queries/--seed/--mode/--data-dir/--generate-sql/--json
     * @return 성공 0, 잘못된 옵션·파일·결과 불일치 1. 생성 데이터는 보고한 경로에 남긴다.
     */
    public static int run(String[] args) {
        try {
            BenchmarkOptions options = new BenchmarkOptions(args);
            if (options.sqlOutput != null) {
                BenchmarkData.generateSql(options);
                System.out.println(JsonCodec.toJson(Map.of("generatedRecords", options.records,
                        "sqlFile", options.sqlOutput.toString())));
                return 0;
            }
            Path dataDir = BenchmarkData.prepare(options);
            Map<String, Object> report = measure(dataDir, options);
            printReport(report, options);
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("[bench] 옵션·빈 데이터 경로·저장 파일 또는 조회 결과를 확인해 주세요");
            return 1;
        }
    }

    private static Map<String, Object> measure(Path dataDir, BenchmarkOptions options) throws IOException {
        long loadElapsed;
        try (DbEngine loader = new DbEngine(dataDir)) { loadElapsed = BenchmarkData.load(loader, options); }
        long started = System.nanoTime();
        try (DbEngine indexed = new DbEngine(dataDir)) {
            long rebuildElapsed = System.nanoTime() - started;
            started = System.nanoTime();
            try (DbEngine scanned = new DbEngine(dataDir, AccessPath.FULL_SCAN)) {
                long scanOpenElapsed = System.nanoTime() - started;
                BenchmarkRunner.Report timings = new BenchmarkRunner(indexed, scanned).run(options);
                Map<String, Object> report = metadata(dataDir, options);
                report.put("loadMs", loadElapsed / NANOSECONDS_PER_MS);
                report.put("rebuildMs", rebuildElapsed / NANOSECONDS_PER_MS);
                report.put("scanOpenMs", scanOpenElapsed / NANOSECONDS_PER_MS);
                report.put("tableBytes", Files.size(dataDir.resolve("products.table")));
                report.put("measurements", timings.measurements()); report.put("randomIds", timings.randomIds());
                return report;
            }
        }
    }

    private static Map<String, Object> metadata(Path dataDir, BenchmarkOptions options) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString()); report.put("records", options.records);
        report.put("dataDir", dataDir.toAbsolutePath().toString());
        report.put("loadMode", options.isDirect ? "direct" : "sql");
        report.put("repeats", options.repeats); report.put("warmups", 2);
        report.put("randomQueries", options.randomQueries); report.put("seed", options.seed);
        report.put("recordBytes", 44); report.put("headerBytes", 44);
        report.put("environment", Map.of("javaVersion", System.getProperty("java.version"),
                "javaVendor", System.getProperty("java.vendor"), "os", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"), "architecture", System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "build", "./mvnw package, release 21"));
        return report;
    }

    private static void printReport(Map<String, Object> report, BenchmarkOptions options) {
        long started = System.nanoTime();
        String json = JsonCodec.toJson(report);
        report.put("jsonPreparationMs", (System.nanoTime() - started) / NANOSECONDS_PER_MS);
        started = System.nanoTime();
        if (options.isJson) { System.out.println(JsonCodec.toJson(report)); }
        else { printTable(report); }
        System.out.flush();
        System.err.printf(Locale.ROOT, "[printReport] JSON/표 변환·출력 %.3fms%n",
                (System.nanoTime() - started) / NANOSECONDS_PER_MS);
    }

    private static void printTable(Map<String, Object> report) {
        System.out.println("records=" + report.get("records") + " dataDir=" + report.get("dataDir"));
        System.out.println("loadMs=" + report.get("loadMs") + " rebuildMs=" + report.get("rebuildMs"));
        System.out.println("scenario | path | mean(ms) | median(ms) | p95(ms) | ratio");
        for (Object value : (List<?>) report.get("measurements")) {
            Map<?, ?> row = (Map<?, ?>) value;
            System.out.printf(Locale.ROOT, "%s | %s | %.6f | %.6f | %.6f | %.2fx%n", row.get("scenario"),
                    row.get("path"), row.get("meanMs"), row.get("medianMs"), row.get("p95Ms"), row.get("ratio"));
        }
    }
}
