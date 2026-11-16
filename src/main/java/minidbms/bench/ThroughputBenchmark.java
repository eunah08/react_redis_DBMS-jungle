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
import minidbms.server.DbWorkerPool;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;

/**
 * 워커 수 1, 2, 4, 8로 서버를 띄워 초당 처리 요청 수를 잰다. (협약 11.2 Phase 3, 권장)
 * 같은 프로세스 안에서 실제 HTTP 요청을 동시 클라이언트로 보낸다. 서버와 클라이언트가 같은 CPU를 나눠 쓰므로
 * 절대값보다 워커 수에 따른 변화를 본다. 단계 : 인덱스 id 조회, 이름 선형 탐색, INSERT.
 */
final class ThroughputBenchmark {

    private static final int[] WORKER_COUNTS = {1, 2, 4, 8};
    private static final int QUEUE_CAPACITY = 4096;
    private static final int LINEAR_DIVISOR = 100;
    private static final int MIN_LINEAR_REQUESTS = 8;

    private final BenchmarkOptions options;
    private final PrintStream out;
    private final HttpClient client = HttpClient.newHttpClient();

    ThroughputBenchmark(BenchmarkOptions options, PrintStream out) {
        this.options = options;
        this.out = out;
    }

    /**
     * 처리량을 재고 표를 출력한다.
     *
     * @param dataDir 비어 있는 데이터 폴더
     * @return 성공하면 0
     * @throws IOException 파일·서버 오류
     */
    int run(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        List<String> rows = new ArrayList<>();
        DbEngine engine = new DbEngine(dataDir);
        try {
            preload(engine);
            for (int workers : WORKER_COUNTS) {
                rows.add(measureWorkers(engine, workers));
            }
        } finally {
            engine.close();
        }
        printReport(rows);
        return 0;
    }

    private void preload(DbEngine engine) {
        out.println("  테이블 준비 " + options.records() + "건");
        for (int serial = 1; serial <= options.records(); serial++) {
            DbResult result = engine.execute(new Statement(StatementType.INSERT, "products",
                    List.of("name", "price"), List.of(BenchmarkRunner.productName(serial), (long) serial), null), true);
            if (result.isError()) {
                throw new IllegalStateException("[preload] 적재 실패: " + result.getMessage());
            }
        }
    }

    private String measureWorkers(DbEngine engine, int workers) throws IOException {
        DbWorkerPool pool = new DbWorkerPool(workers, QUEUE_CAPACITY);
        DbHttpServer server = new DbHttpServer(engine::execute, pool, "127.0.0.1", 0);
        server.start();
        try {
            int linearRequests = Math.max(MIN_LINEAR_REQUESTS, options.requests() / LINEAR_DIVISOR);
            Random random = new Random(options.seed());
            int recordCount = Math.max(options.records(), 1);
            double indexRate = measure(server.getPort(), options.requests(),
                    i -> "SELECT * FROM products WHERE id = " + (random.nextInt(recordCount) + 1) + ";");
            double linearRate = measure(server.getPort(), linearRequests,
                    i -> "SELECT * FROM products WHERE name = '" + BenchmarkRunner.productName(i % recordCount + 1) + "';");
            double insertRate = measure(server.getPort(), options.requests(),
                    i -> "INSERT INTO products (name, price) VALUES ('t" + workers + "_" + i + "', " + i + ");");
            return String.format(Locale.ROOT, "| %d | %.0f | %.1f | %.0f |", workers, indexRate, linearRate, insertRate);
        } finally {
            server.stop();
        }
    }

    /** 동시 클라이언트로 요청을 모두 보내고 초당 처리 수를 돌려준다. 200이 아닌 응답이 있으면 실패다. */
    private double measure(int port, int requestCount, IntFunction<String> sqlSupplier) {
        ExecutorService clients = Executors.newFixedThreadPool(options.clients());
        AtomicInteger next = new AtomicInteger();
        long start = System.nanoTime();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int c = 0; c < options.clients(); c++) {
                futures.add(clients.submit(() -> sendUntilDone(port, requestCount, next, sqlSupplier)));
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
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        return requestCount / seconds;
    }

    private int sendUntilDone(int port, int requestCount, AtomicInteger next, IntFunction<String> sqlSupplier)
            throws IOException, InterruptedException {
        int failures = 0;
        int index = next.getAndIncrement();
        while (index < requestCount) {
            String body = "{\"sql\":" + JsonWriter.quote(sqlSupplier.apply(index)) + "}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sql"))
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
        int linearRequests = Math.max(MIN_LINEAR_REQUESTS, options.requests() / LINEAR_DIVISOR);
        out.println();
        out.println("## 워커 수별 처리량");
        out.println();
        out.println("- 실행 환경: JDK " + Runtime.version() + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", CPU 코어 " + Runtime.getRuntime().availableProcessors() + "개");
        out.println("- 데이터 " + options.records() + "건, 동시 클라이언트 " + options.clients() + "개, 단계별 요청 "
                + options.requests() + "건 (선형 탐색 " + linearRequests + "건), 서버와 클라이언트가 같은 프로세스");
        out.println();
        out.println("| 워커 수 | id 조회(인덱스) req/s | name 조회(선형) req/s | INSERT req/s |");
        out.println("|---|---|---|---|");
        rows.forEach(out::println);
    }
}
