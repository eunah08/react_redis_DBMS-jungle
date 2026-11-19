package minidbms.bench;

/**
 * 공통 데이터 생성·적재와 조회 성능 비교. (협약 12장)
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    /**
     * bench 명령을 실행한다.
     *
     * @param args "bench" 뒤의 인자. --records 건수 (그 밖의 옵션 이름은 각자 정한다)
     * @return 성공하면 0, 실패하면 1
     */
    public static int run(String[] args) {
        // TODO(이니셜): 데이터 생성 → 예열 → 인덱스·선형 번갈아 10회 측정 → 표 출력 (협약 12장)
        throw new UnsupportedOperationException("[run] bench 명령은 아직 구현되지 않았습니다");
    }
}
