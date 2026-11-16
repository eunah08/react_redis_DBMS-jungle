package minidbms.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 측정한 시간(나노초)을 모아 평균·중앙값·p95를 밀리초로 돌려준다. (협약 12장 기록 양식)
 */
final class LatencyStats {

    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double P95 = 0.95;

    private final List<Long> samplesNanos = new ArrayList<>();

    /**
     * @param elapsedNanos 한 번 잰 시간
     */
    void add(long elapsedNanos) {
        samplesNanos.add(elapsedNanos);
    }

    /**
     * @return 모은 횟수
     */
    int count() {
        return samplesNanos.size();
    }

    /**
     * @return 평균(ms)
     */
    double meanMs() {
        long total = 0;
        for (long sample : samplesNanos) {
            total += sample;
        }
        return total / (double) samplesNanos.size() / NANOS_PER_MILLI;
    }

    /**
     * @return 중앙값(ms)
     */
    double medianMs() {
        List<Long> sorted = sortedSamples();
        int middle = sorted.size() / 2;
        long medianNanos = sorted.size() % 2 == 1
                ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        return medianNanos / NANOS_PER_MILLI;
    }

    /**
     * @return p95(ms). 가장 가까운 순위 방식이다
     */
    double p95Ms() {
        List<Long> sorted = sortedSamples();
        int rank = (int) Math.ceil(P95 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1) / NANOS_PER_MILLI;
    }

    private List<Long> sortedSamples() {
        List<Long> sorted = new ArrayList<>(samplesNanos);
        Collections.sort(sorted);
        return sorted;
    }
}
