package minidbms.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** nanoTime 표본의 평균·중앙값·p95를 밀리초로 집계한다. */
final class BenchmarkStats {
    private static final double NANOSECONDS_PER_MS = 1000000.0;
    private BenchmarkStats() { }

    static Map<String, Object> summarize(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int size = sorted.size();
        double median = size % 2 == 0 ? sorted.get(size / 2 - 1) / 2.0
                + sorted.get(size / 2) / 2.0 : sorted.get(size / 2);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meanMs", samples.stream().mapToLong(Long::longValue).average().orElseThrow() / NANOSECONDS_PER_MS);
        result.put("medianMs", median / NANOSECONDS_PER_MS);
        result.put("p95Ms", sorted.get((int) Math.ceil(size * 0.95) - 1) / NANOSECONDS_PER_MS);
        result.put("samplesNs", List.copyOf(samples));
        return result;
    }
}
