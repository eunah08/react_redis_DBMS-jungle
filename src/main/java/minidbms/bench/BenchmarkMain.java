package minidbms.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 공통 데이터 생성·적재와 조회 성능 비교. (협약 12장)
 * 옵션은 BenchmarkOptions 설명을 본다. 건수마다 빈 데이터 폴더에서 새로 적재한다.
 * 데이터 폴더를 지정하지 않으면 임시 폴더를 쓰고 끝나면 지운다.
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    /**
     * bench 명령을 실행한다.
     *
     * @param args "bench" 뒤의 인자. --records 건수 (그 밖의 옵션은 BenchmarkOptions 참고)
     * @return 성공하면 0, 실패하면 1
     */
    public static int run(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            BenchmarkOptions options = BenchmarkOptions.parse(args);
            if (options.generateSqlPath() != null) {
                int records = options.recordSteps().get(options.recordSteps().size() - 1);
                SqlFileGenerator.write(options.generateSqlPath(), records);
                out.println("SQL 파일을 만들었습니다 : " + options.generateSqlPath() + " (" + records + "건)");
                return 0;
            }
            printEnvironment(options, out);
            if (options.isThroughput()) {
                runThroughput(options, out);
                return 0;
            }
            for (int records : options.recordSteps()) {
                runStep(options, records, out);
            }
            return 0;
        } catch (IllegalArgumentException | IOException | IllegalStateException e) {
            System.err.println("[run] 벤치마크를 끝내지 못했습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void runThroughput(BenchmarkOptions options, PrintStream out) throws IOException {
        boolean isTemporary = options.dataDir() == null;
        Path dataDir = isTemporary ? Files.createTempDirectory("minidbms-bench") : options.dataDir();
        try {
            new ThroughputBenchmark(options, options.recordSteps().get(0), out).run(dataDir);
        } finally {
            if (isTemporary) {
                deleteRecursively(dataDir);
            }
        }
    }

    private static void runStep(BenchmarkOptions options, int records, PrintStream out) throws IOException {
        boolean isTemporary = options.dataDir() == null;
        Path dataDir = isTemporary
                ? Files.createTempDirectory("minidbms-bench")
                : options.dataDir().resolve("records_" + records);
        try {
            BenchmarkRunner runner = new BenchmarkRunner(options, records, dataDir, out);
            runner.run();
            runner.describeFiles().forEach(line -> out.println("파일 : " + line));
        } finally {
            if (isTemporary) {
                deleteRecursively(dataDir);
            }
        }
    }

    private static void printEnvironment(BenchmarkOptions options, PrintStream out) {
        out.println("# 벤치마크 실행 환경 (README와 implementation_notes에 함께 적는다)");
        out.println("- JDK : " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")");
        out.println("- OS : " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        out.println("- CPU 수 : " + Runtime.getRuntime().availableProcessors()
                + ", 최대 힙 : " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        out.println("- 건수 단계 : " + options.recordSteps() + ", 레코드 : name 최대 32바이트 + price 정수 + id");
        out.println("- 시드 : " + options.seed() + ", 반복 : " + options.repeat() + "회, 적재 방식 : "
                + (options.isDirectLoad() ? "엔진 직접 삽입" : "SQL 처리기 INSERT"));
    }

    private static void deleteRecursively(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
