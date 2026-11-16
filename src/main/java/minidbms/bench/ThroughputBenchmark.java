package minidbms.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.json.JsonWriter;
import minidbms.server.DbHttpServer;
import minidbms.server.ServerOptions;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;

/**
 * 워커 수 1, 2, 4, 8로 서버를 띄워 초당 처리 요청 수를 잰다. (협약 11.2 Phase 3, 권장)
 * 같은 프로세스 안에서 실제 HTTP 요청을 동시 클라이언트로 보낸다. 서버와 클라이언트가 같은 CPU를 나눠 쓰므로
 * 절대값보다 워커 수에 따른 변화를 본다. 단계 : 인덱스 id 조회, 이름 선형 탐색, INSERT.
 * 처음 한 번 데이터를 적재하고 워커 수마다 서버를 새로 띄운다. (INSERT 단계는 테이블을 늘리므로 단계 순서를 고정한다)
 */
final class ThroughputBenchmark {

    private static final int[] WORKER_COUNTS = {1, 2, 4, 8};
    private static final int QUEUE_CAPACITY = 4096;
    private static final int LINEAR_DIVISOR = 100;
    private static final int MIN_LINEAR_REQUESTS = 8;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final BenchmarkOptions options;
    private final int records;
    private final PrintStream out;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /**
     * @param options 옵션 (requests, clients, seed를 쓴다)
     * @param records 미리 적재할 건수
     * @param out     결과를 쓸 곳
     */
    ThroughputBenchmark(BenchmarkOptions options, int records, PrintStream out) {
        this.options = options;
        this.records = records;
        this.out = out;
    }

    /**
     * 처리량을 재고 표를 출력한다.
     *
     * @param dataDir 비어 있는 데이터 폴더
     * @throws IOException 파일·서버 오류
     */
    void run(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n", StandardCharsets.UTF_8);
        preload(dataDir);
        List<String> rows = new ArrayList<>();
        for (int workers : WORKER_COUNTS) {
            rows.add(measureWorkers(dataDir, workers));
        }
        printReport(rows);
    }

    private void preload(Path dataDir) {
        out.println("  테이블 준비 " + records + "건");
        DbEngine engine = new DbEngine(dataDir);
        try {
            for (int serial = 1; serial <= records; serial++) {
                DbResult result = engine.executeStatement(new Statement(StatementType.INSERT, "products",
                        List.of("name", "price"), List.of(BenchmarkRunner.productName(serial), (long) serial), null));
                if (result.isError()) {
                    throw new IllegalStateException("[preload] 적재 실패: " + result.getErrorMessage());
                }
            }
        } finally {
            engine.close();
        }
    }

    private String measureWorkers(Path dataDir, int workers) throws IOException {
        DbHttpServer server = new DbHttpServer(new ServerOptions("127.0.0.1", 0, workers, QUEUE_CAPACITY, dataDir));
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getPort() + "/sql");
            int linearRequests = linearRequestCount();
            Random random = new Random(options.seed());
            double indexRate = measure(new Phase(uri, options.requests(),
                    i -> "SELECT * FROM products WHERE id = " + (random.nextInt(records) + 1) + ";"));
            double linearRate = measure(new Phase(uri, linearRequests,
                    i -> "SELECT * FROM products WHERE name = '" + BenchmarkRunner.productName(i % records + 1) + "';"));
            double insertRate = measure(new Phase(uri, options.requests(),
                    i -> "INSERT INTO products (name, price) VALUES ('t" + workers + "_" + i + "', " + i + ");"));
            return String.format(Locale.ROOT, "| %d | %.0f | %.1f | %.0f |", workers, indexRate, linearRate, insertRate);
        } finally {
            server.stop();
        }
    }

    private int linearRequestCount() {
        return Math.max(MIN_LINEAR_REQUESTS, options.requests() / LINEAR_DIVISOR);
    }

    /** 동시 클라이언트로 요청을 모두 보내고 초당 처리 수를 돌려준다. 200이 아닌 응답이 있으면 실패다. */
    private double measure(Phase phase) {
        ExecutorService clients = Executors.newFixedThreadPool(options.clients());
        AtomicInteger next = new AtomicInteger();
        long start = System.nanoTime();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < options.clients(); i++) {
                futures.add(clients.submit(() -> sendUntilDone(phase, next)));
            }
            int failures = 0;
            for (Future<Integer> future : futures) {
                failures += future.get();
            }
            if (failures > 0) {
                throw new IllegalStateException("[measure] 200이 아닌 응답이 " + failures + "건 있었습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[measure] 측정이 중단되었습니다", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("[measure] 요청을 보내지 못했습니다: " + e.getCause(), e);
        } finally {
            clients.shutdownNow();
        }
        return phase.requestCount() / ((System.nanoTime() - start) / NANOS_PER_SECOND);
    }

    /** 공용 번호표(next)를 하나씩 받아 요청을 보낸다. 모든 번호가 나가면 멈춘다. */
    private int sendUntilDone(Phase phase, AtomicInteger next) throws IOException, InterruptedException {
        int failures = 0;
        int index = next.getAndIncrement();
        while (index < phase.requestCount()) {
            String body = "{\"sql\":" + JsonWriter.quote(phase.sqlSupplier().apply(index)) + "}";
            HttpRequest request = HttpRequest.newBuilder(phase.uri())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                failures++;
            }
            index = next.getAndIncrement();
        }
        return failures;
    }

    private void printReport(List<String> rows) {
        out.println();
        out.println("## 워커 수별 처리량");
        out.println();
        out.println("- 실행 환경 : JDK " + Runtime.version() + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", CPU 코어 " + Runtime.getRuntime().availableProcessors() + "개");
        out.println("- 데이터 " + records + "건, 동시 클라이언트 " + options.clients() + "개, 단계별 요청 "
                + options.requests() + "건 (선형 탐색 " + linearRequestCount() + "건), 서버와 클라이언트가 같은 프로세스");
        out.println();
        out.println("| 워커 수 | id 조회(인덱스) req/s | name 조회(선형) req/s | INSERT req/s |");
        out.println("|---|---|---|---|");
        rows.forEach(out::println);
    }

    /** 처리량 측정 한 단계 : 어디로, 몇 건을, 몇 번째 요청에 어떤 SQL을 보낼지. */
    private record Phase(URI uri, int requestCount, IntFunction<String> sqlSupplier) {
    }
}
