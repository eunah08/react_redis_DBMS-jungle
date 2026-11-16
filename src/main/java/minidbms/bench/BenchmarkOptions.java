package minidbms.bench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bench 명령의 옵션. (협약 12장 : 옵션 이름은 각자 정한다)
 *
 * <pre>
 * bench --records 1000000            적재할 건수 (기본 1,000,000)
 *       --load sql|direct            sql : SQL 문장을 만들어 처리기로 넣는다 (기본)
 *                                    direct : 파싱을 건너뛰고 엔진에 바로 넣는다
 *       --generate-sql 파일          SQL 파일만 만들고 끝낸다 (처리기로 따로 넣어 볼 때)
 *       --data-dir 경로              데이터 폴더. 비어 있어야 한다. 없으면 임시 폴더를 쓰고 지운다
 *       --steps 10000,100000         측정할 건수 단계 (기본 10000, 100000, 1000000 중 records 이하 + records)
 *       --seed 20261116              무작위 id 시드 (기본 20261116)
 *       --repeat 10 --warmup 3       번갈아 측정할 횟수와 예열 횟수
 *       --random-count 1000          무작위 id 인덱스 조회 횟수
 *       --linear-random-count 20     무작위 id 선형 탐색 횟수 (한 번이 오래 걸려 적게 잡는다)
 * </pre>
 *
 * <p>처리량 모드 (협약 11.2 Phase 3) : {@code bench --mode throughput [--records 100000] [--requests 2000]
 * [--clients 16]}. 같은 데이터로 워커 수 1, 2, 4, 8의 서버를 차례로 띄워 초당 요청 수를 잰다.
 *
 * @param records           적재할 건수
 * @param loadMode          적재 방법
 * @param generateSqlFile   null이 아니면 SQL 파일만 만든다
 * @param dataDir           null이면 임시 폴더
 * @param steps             측정할 건수 단계 (오름차순, 마지막은 records)
 * @param seed              무작위 시드
 * @param repeat            번갈아 측정할 횟수
 * @param warmup            예열 횟수
 * @param randomCount       무작위 id 인덱스 조회 횟수
 * @param linearRandomCount 무작위 id 선형 탐색 횟수
 * @param isThroughputMode  true이면 지연 시간 대신 워커 수별 처리량을 잰다
 * @param requests          처리량 모드의 단계별 요청 수 (선형 탐색 단계는 이 값의 1/100, 최소 8)
 * @param clients           처리량 모드의 동시 클라이언트 수
 */
record BenchmarkOptions(
        int records,
        LoadMode loadMode,
        Path generateSqlFile,
        Path dataDir,
        List<Integer> steps,
        long seed,
        int repeat,
        int warmup,
        int randomCount,
        int linearRandomCount,
        boolean isThroughputMode,
        int requests,
        int clients) {

    /** 적재 방법. */
    enum LoadMode {
        SQL,
        DIRECT
    }

    private static final int DEFAULT_RECORDS = 1_000_000;
    private static final List<Integer> DEFAULT_STEPS = List.of(10_000, 100_000, 1_000_000);

    /**
     * 인자를 읽는다.
     *
     * @param args "bench" 뒤의 인자
     * @return 옵션
     * @throws IllegalArgumentException 모르는 옵션, 값 누락, 숫자 오류
     */
    static BenchmarkOptions parse(String[] args) {
        Builder builder = new Builder();
        for (int i = 0; i < args.length; i += 2) {
            builder.apply(args[i], readValue(args, i));
        }
        return builder.build();
    }

    /** 옵션 값을 기본값에서 시작해 하나씩 덮어쓴다. */
    private static final class Builder {
        private int records = DEFAULT_RECORDS;
        private LoadMode loadMode = LoadMode.SQL;
        private Path generateSqlFile;
        private Path dataDir;
        private String stepsText;
        private long seed = 20261116L;
        private int repeat = 10;
        private int warmup = 3;
        private int randomCount = 1000;
        private int linearRandomCount = 20;
        private boolean isThroughputMode;
        private int requests = 2000;
        private int clients = 16;

        void apply(String option, String value) {
            switch (option) {
                case "--records" -> records = toNonNegativeInt(option, value);
                case "--load" -> loadMode = toLoadMode(value);
                case "--generate-sql" -> generateSqlFile = Path.of(value);
                case "--data-dir" -> dataDir = Path.of(value);
                case "--steps" -> stepsText = value;
                case "--seed" -> seed = Long.parseLong(value);
                case "--repeat" -> repeat = toPositiveInt(option, value);
                case "--warmup" -> warmup = toNonNegativeInt(option, value);
                case "--random-count" -> randomCount = toNonNegativeInt(option, value);
                case "--linear-random-count" -> linearRandomCount = toNonNegativeInt(option, value);
                case "--mode" -> isThroughputMode = toThroughputFlag(value);
                case "--requests" -> requests = toPositiveInt(option, value);
                case "--clients" -> clients = toPositiveInt(option, value);
                default -> throw new IllegalArgumentException("[parse] 알 수 없는 옵션입니다: " + option);
            }
        }

        BenchmarkOptions build() {
            List<Integer> steps = stepsText == null ? defaultSteps(records) : parseSteps(stepsText, records);
            return new BenchmarkOptions(records, loadMode, generateSqlFile, dataDir, steps, seed,
                    repeat, warmup, randomCount, linearRandomCount, isThroughputMode, requests, clients);
        }
    }

    private static boolean toThroughputFlag(String value) {
        return switch (value) {
            case "latency" -> false;
            case "throughput" -> true;
            default -> throw new IllegalArgumentException("[parse] --mode는 latency 또는 throughput입니다: " + value);
        };
    }

    private static String readValue(String[] args, int optionIndex) {
        if (!args[optionIndex].startsWith("--")) {
            throw new IllegalArgumentException("[parse] 옵션이 아닌 인자입니다: " + args[optionIndex]);
        }
        if (optionIndex + 1 >= args.length) {
            throw new IllegalArgumentException("[parse] " + args[optionIndex] + " 뒤에 값이 필요합니다");
        }
        return args[optionIndex + 1];
    }

    private static List<Integer> defaultSteps(int records) {
        List<Integer> steps = new ArrayList<>();
        for (int step : DEFAULT_STEPS) {
            if (step < records) {
                steps.add(step);
            }
        }
        steps.add(records);
        return steps;
    }

    private static List<Integer> parseSteps(String stepsText, int records) {
        List<Integer> steps = new ArrayList<>();
        for (String part : stepsText.split(",")) {
            int step = toPositiveInt("--steps", part.trim());
            if (step > records || (!steps.isEmpty() && step <= steps.get(steps.size() - 1))) {
                throw new IllegalArgumentException("[parse] --steps는 records 이하의 오름차순이어야 합니다");
            }
            steps.add(step);
        }
        if (steps.get(steps.size() - 1) != records) {
            steps.add(records);
        }
        return steps;
    }

    private static LoadMode toLoadMode(String value) {
        return switch (value) {
            case "sql" -> LoadMode.SQL;
            case "direct" -> LoadMode.DIRECT;
            default -> throw new IllegalArgumentException("[parse] --load는 sql 또는 direct입니다: " + value);
        };
    }

    private static int toNonNegativeInt(String option, String value) {
        try {
            int number = Integer.parseInt(value);
            if (number >= 0) {
                return number;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("[parse] " + option + " 값이 숫자가 아닙니다: " + value);
        }
        throw new IllegalArgumentException("[parse] " + option + " 값은 0 이상이어야 합니다: " + value);
    }

    private static int toPositiveInt(String option, String value) {
        int number = toNonNegativeInt(option, value);
        if (number == 0) {
            throw new IllegalArgumentException("[parse] " + option + " 값은 1 이상이어야 합니다");
        }
        return number;
    }
}
