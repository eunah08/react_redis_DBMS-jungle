package minidbms.bench;

import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import minidbms.storage.StorageException;

/**
 * 공통 데이터 생성·적재와 조회 성능 비교. (협약 12장)
 * 데이터는 소문자 products(id, name, price)이며 이름은 product_0000001부터 7자리 일련번호,
 * price는 일련번호와 같다. 생성한 대용량 파일은 저장소에 올리지 않는다. (협약 5장)
 */
public final class BenchmarkMain {

    private static final int EXIT_FAILURE = 1;
    private static final String USAGE = "사용법: java -jar target/mini-dbms.jar bench [--records 건수] [--load sql|direct]"
            + " [--generate-sql 파일] [--data-dir 경로] [--steps 10000,100000] [--seed 값]"
            + " [--repeat 횟수] [--warmup 횟수] [--random-count 횟수] [--linear-random-count 횟수]";

    private BenchmarkMain() {
    }

    /**
     * bench 명령을 실행한다. 보고서는 UTF-8로 System.out에 쓴다.
     *
     * @param args "bench" 뒤의 인자. --records 건수 (그 밖의 옵션 이름은 BenchmarkOptions 참고)
     * @return 성공하면 0, 실패하면 1
     */
    public static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        return run(args, out);
    }

    /**
     * 출력 위치를 정해 bench 명령을 실행한다. 테스트가 보고서를 모을 때 쓴다.
     *
     * @param args "bench" 뒤의 인자
     * @param out  보고서를 쓸 곳
     * @return 성공하면 0, 실패하면 1
     */
    public static int run(String[] args, PrintStream out) {
        BenchmarkOptions options;
        try {
            options = BenchmarkOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return EXIT_FAILURE;
        }
        try {
            if (options.generateSqlFile() != null) {
                writeSqlFile(options, out);
                return 0;
            }
            return runWithDataDir(options, out);
        } catch (IOException | StorageException | IllegalStateException | IllegalArgumentException e) {
            System.err.println("[run] 벤치마크 실패: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static int runWithDataDir(BenchmarkOptions options, PrintStream out) throws IOException {
        boolean isTemporary = options.dataDir() == null;
        Path dataDir = isTemporary ? Files.createTempDirectory("mini-dbms-bench") : options.dataDir();
        try {
            if (options.isThroughputMode()) {
                return new ThroughputBenchmark(options, out).run(dataDir);
            }
            return new BenchmarkRunner(options, dataDir, out).run();
        } finally {
            if (isTemporary) {
                deleteRecursively(dataDir);
            }
        }
    }

    /** INSERT 문장만 담은 SQL 파일을 만든다. 0건이면 빈 파일이다. */
    private static void writeSqlFile(BenchmarkOptions options, PrintStream out) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(options.generateSqlFile(), StandardCharsets.UTF_8)) {
            for (int serial = 1; serial <= options.records(); serial++) {
                writer.write("INSERT INTO products (name, price) VALUES ('" + BenchmarkRunner.productName(serial)
                        + "', " + serial + ");");
                writer.newLine();
            }
        }
        out.println("SQL " + options.records() + "건을 만들었습니다: " + options.generateSqlFile());
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
