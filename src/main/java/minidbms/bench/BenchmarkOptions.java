package minidbms.bench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bench 명령의 옵션. (협약 12장 : 옵션 이름은 각자 정한다)
 *
 * <pre>
 * --records 10000,100000,1000000   건수 단계 (쉼표로 여러 개). 기본값 1000000
 * --load-mode parse|direct         parse : 공통 SQL 처리기로 INSERT (기본), direct : 파싱을 건너뛰고 엔진에 바로 넣기
 * --generate-sql 경로              측정하지 않고 INSERT SQL 파일만 만든다 (건수는 --records의 마지막 값)
 * --seed 42                        무작위 id를 고르는 시드
 * --repeat 10                      측정 반복 횟수
 * --random-count 1000              무작위 id 조회 횟수 (선형 탐색은 최대 100회로 줄인다)
 * --data-dir 경로                   데이터 폴더의 위치. 없으면 임시 폴더를 만들고 끝나면 지운다
 * </pre>
 *
 * @param recordSteps     건수 단계
 * @param isDirectLoad    파싱을 건너뛰고 적재하면 true
 * @param generateSqlPath SQL 파일만 만들 경로. 없으면 null
 * @param seed            무작위 시드
 * @param repeat          측정 반복 횟수
 * @param randomCount     무작위 id 조회 횟수
 * @param dataDir         데이터 폴더 위치. 없으면 null
 */
record BenchmarkOptions(List<Integer> recordSteps, boolean isDirectLoad, Path generateSqlPath,
                        long seed, int repeat, int randomCount, Path dataDir) {

    private static final int DEFAULT_RECORDS = 1_000_000;
    private static final long DEFAULT_SEED = 42;
    private static final int DEFAULT_REPEAT = 10;
    private static final int DEFAULT_RANDOM_COUNT = 1000;

    /**
     * @param args "bench" 뒤의 인자
     * @return 옵션
     * @throws IllegalArgumentException 모르는 옵션이거나 값이 올바르지 않을 때
     */
    static BenchmarkOptions parse(String[] args) {
        BenchmarkOptions options = new BenchmarkOptions(List.of(DEFAULT_RECORDS), false, null,
                DEFAULT_SEED, DEFAULT_REPEAT, DEFAULT_RANDOM_COUNT, null);
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(args[i] + " 뒤에 값이 필요합니다");
            }
            options = options.withOption(args[i], args[i + 1]);
        }
        return options;
    }

    private BenchmarkOptions withOption(String name, String value) {
        return switch (name) {
            case "--records" -> new BenchmarkOptions(toSteps(value), isDirectLoad, generateSqlPath, seed,
                    repeat, randomCount, dataDir);
            case "--load-mode" -> new BenchmarkOptions(recordSteps, toDirectLoad(value), generateSqlPath, seed,
                    repeat, randomCount, dataDir);
            case "--generate-sql" -> new BenchmarkOptions(recordSteps, isDirectLoad, Path.of(value), seed,
                    repeat, randomCount, dataDir);
            case "--seed" -> new BenchmarkOptions(recordSteps, isDirectLoad, generateSqlPath, toLong(name, value),
                    repeat, randomCount, dataDir);
            case "--repeat" -> new BenchmarkOptions(recordSteps, isDirectLoad, generateSqlPath, seed,
                    toPositive(name, value), randomCount, dataDir);
            case "--random-count" -> new BenchmarkOptions(recordSteps, isDirectLoad, generateSqlPath, seed,
                    repeat, toPositive(name, value), dataDir);
            case "--data-dir" -> new BenchmarkOptions(recordSteps, isDirectLoad, generateSqlPath, seed,
                    repeat, randomCount, Path.of(value));
            default -> throw new IllegalArgumentException("알 수 없는 옵션입니다: " + name);
        };
    }

    private static List<Integer> toSteps(String value) {
        List<Integer> steps = new ArrayList<>();
        for (String part : value.split(",")) {
            steps.add(toPositive("--records", part.strip()));
        }
        return steps;
    }

    private static boolean toDirectLoad(String value) {
        if (value.equals("direct")) {
            return true;
        }
        if (value.equals("parse")) {
            return false;
        }
        throw new IllegalArgumentException("--load-mode는 parse 또는 direct여야 합니다: " + value);
    }

    private static int toPositive(String name, String value) {
        long number = toLong(name, value);
        if (number < 1 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " 값은 1 이상 정수여야 합니다: " + value);
        }
        return (int) number;
    }

    private static long toLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 값이 숫자가 아닙니다: " + value);
        }
    }
}
