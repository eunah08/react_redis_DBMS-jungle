package minidbms.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 측정한 시간 목록의 평균·중앙값·p95. (협약 12장 : 중앙값을 기본으로 하고 평균·p95를 더한다)
 *
 * @param meanMs   평균(ms)
 * @param medianMs 중앙값(ms)
 * @param p95Ms    95번째 백분위(ms, 가까운 순위 방식)
 */
record QueryStats(double meanMs, double medianMs, double p95Ms) {

    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final double PERCENTILE = 0.95;

    /**
     * 나노초 목록을 요약한다.
     *
     * @param elapsedNanos System.nanoTime 차이 목록 (비어 있으면 안 된다)
     * @return 요약
     */
    static QueryStats summarize(List<Long> elapsedNanos) {
        if (elapsedNanos.isEmpty()) {
            throw new IllegalArgumentException("[summarize] 측정값이 없습니다");
        }
        List<Long> sorted = new ArrayList<>(elapsedNanos);
        Collections.sort(sorted);
        double sum = 0;
        for (long nanos : sorted) {
            sum += nanos;
        }
        int count = sorted.size();
        double median = count % 2 == 1
                ? sorted.get(count / 2)
                : (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2.0;
        int p95Index = (int) Math.ceil(PERCENTILE * count) - 1;
        return new QueryStats(sum / count / NANOS_PER_MS, median / NANOS_PER_MS,
                sorted.get(Math.max(p95Index, 0)) / NANOS_PER_MS);
    }
}
