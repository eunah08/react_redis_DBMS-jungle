package minidbms.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import minidbms.engine.AccessPath;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;

/** 같은 행을 조회하는 세 경로를 예열 후 교대 측정하고 결과까지 비교한다. */
final class BenchmarkRunner {
    private static final int WARMUP_REPEATS = 2;
    private static final int PROGRESS_INTERVAL = 100;
    private record Variant(String path, DbEngine engine, String sqlText) { }
    private record Sample(long elapsedNs, List<Map<String, Object>> rows) { }
    record Report(List<Map<String, Object>> measurements, List<Integer> randomIds) { }
    private final DbEngine indexed;
    private final DbEngine scanned;
    private final List<Map<String, Object>> measurements = new ArrayList<>();

    BenchmarkRunner(DbEngine indexed, DbEngine scanned) {
        this.indexed = indexed;
        this.scanned = scanned;
    }

    Report run(BenchmarkOptions options) {
        measureCase("first", 1, options);
        measureCase("middle", Math.max(1, options.records / 2), options);
        measureCase("last", Math.max(1, options.records), options);
        measureCase("missing", options.records == Integer.MAX_VALUE ? 0 : options.records + 1, options);
        List<Integer> randomIds = measureRandom(options);
        return new Report(List.copyOf(measurements), List.copyOf(randomIds));
    }

    private List<Variant> variants(int id) {
        String byId = "SELECT * FROM products WHERE id = " + id + ";";
        String byName = "SELECT * FROM products WHERE name = '" + BenchmarkData.productName(id) + "';";
        return List.of(new Variant("INDEX_ID", indexed, byId), new Variant("SCAN_ID", scanned, byId),
                new Variant("SCAN_NAME", indexed, byName));
    }

    private void measureCase(String label, int id, BenchmarkOptions options) {
        List<Variant> queries = variants(id);
        List<List<Long>> samples = sampleLists();
        List<Map<String, Object>> expected = execute(queries.get(0)).rows();
        for (int repeat = 0; repeat < WARMUP_REPEATS + options.repeats; repeat++) {
            for (int step = 0; step < queries.size(); step++) {
                int index = repeat % 2 == 0 ? step : queries.size() - 1 - step;
                Sample sample = execute(queries.get(index));
                if (!sample.rows().equals(expected)) { throw new IllegalStateException("[measureCase] 결과 불일치"); }
                if (repeat >= WARMUP_REPEATS) { samples.get(index).add(sample.elapsedNs()); }
            }
        }
        addMeasurements(label, samples, expected.size());
    }

    private List<Integer> measureRandom(BenchmarkOptions options) {
        List<Integer> ids = new ArrayList<>();
        List<List<Long>> samples = sampleLists();
        Random random = new Random(options.seed);
        for (int sampleIndex = 0; sampleIndex < options.randomQueries; sampleIndex++) {
            int id = random.nextInt(Math.max(1, options.records)) + 1;
            ids.add(id);
            List<Variant> queries = variants(id);
            List<Map<String, Object>> expected = execute(queries.get(0)).rows();
            for (int step = 0; step < queries.size(); step++) {
                int index = sampleIndex % 2 == 0 ? step : queries.size() - 1 - step;
                Sample sample = execute(queries.get(index));
                if (!sample.rows().equals(expected)) { throw new IllegalStateException("[measureRandom] 결과 불일치"); }
                samples.get(index).add(sample.elapsedNs());
            }
            if ((sampleIndex + 1) % PROGRESS_INTERVAL == 0) {
                System.err.println("[measureRandom] " + (sampleIndex + 1) + "/" + options.randomQueries);
            }
        }
        if (!ids.isEmpty()) { addMeasurements("random", samples, options.records == 0 ? 0 : 1); }
        return ids;
    }

    private Sample execute(Variant variant) {
        long start = System.nanoTime();
        DbResult result = variant.engine().execute(variant.sqlText());
        long elapsedNs = System.nanoTime() - start;
        AccessPath expected = variant.path().equals("INDEX_ID") ? AccessPath.INDEX : AccessPath.FULL_SCAN;
        if (!result.isSuccess() || result.getAccessPath() != expected) {
            throw new IllegalStateException("[measure] 오류 또는 접근 경로 불일치");
        }
        return new Sample(elapsedNs, result.getRows());
    }

    private List<List<Long>> sampleLists() {
        return List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private void addMeasurements(String label, List<List<Long>> samples, int rowCount) {
        List<String> paths = List.of("INDEX_ID", "SCAN_ID", "SCAN_NAME");
        double indexedMedian = 0;
        for (int index = 0; index < paths.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", label); row.put("path", paths.get(index));
            row.put("accessPath", index == 0 ? "INDEX" : "FULL_SCAN");
            row.put("sampleCount", samples.get(index).size()); row.put("rowCount", rowCount);
            row.putAll(BenchmarkStats.summarize(samples.get(index)));
            if (index == 0) { indexedMedian = ((Number) row.get("medianMs")).doubleValue(); }
            row.put("ratio", ((Number) row.get("medianMs")).doubleValue() / Math.max(0.000001, indexedMedian));
            measurements.add(row);
        }
    }
}
