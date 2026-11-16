package minidbms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import minidbms.TestSupport;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import minidbms.json.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 10.3, 11.2 Phase 3, 13장 서버 항목 : 실제 서버에 보낸 HTTP 요청으로 확인한다.
 */
class DbHttpServerTest {

    private static final long WAIT_SECONDS = 15;

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private DbEngine engine;
    private DbWorkerPool pool;
    private DbHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (engine != null) {
            engine.close();
        }
    }

    // ---- 정상 동작 ----

    @Test
    void health는_200과_ok를_돌려준다() throws Exception {
        startRealServer(2, 16);

        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"ok\"}", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void 공통_fixture의_성공_JSON을_돌려준다() throws Exception {
        startRealServer(2, 16);

        assertEquals("{\"id\":1}", sql("INSERT INTO products (name, price) VALUES ('키보드', 35000);").body());
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\",\"price\":35000}]}", sql("SELECT * FROM products;").body());
        assertEquals("{\"rows\":[{\"name\":\"키보드\",\"price\":35000}]}",
                sql("SELECT name, price FROM products WHERE price = 35000;").body());
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\",\"price\":35000}]}",
                sql("SELECT * FROM products WHERE id = 1;").body());
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\",\"price\":35000}]}",
                sql("SELECT * FROM products WHERE name = '키보드';").body());
        assertEquals("{\"rows\":[]}", sql("SELECT * FROM products WHERE id = 2;").body());
        HttpResponse<String> all = sql("SELECT * FROM products;");
        assertEquals(200, all.statusCode());
    }

    @Test
    void 요청_JSON의_이스케이프와_한글_이모지를_처리한다() throws Exception {
        startRealServer(2, 16);
        String insert = "INSERT INTO products (name, price) VALUES ('따옴표\"역슬래시\\줄\n😀', 1);";

        HttpResponse<String> inserted = post("/sql", "{\"sql\":" + JsonWriter.quote(insert) + "}", "application/json");
        HttpResponse<String> selected = sql("SELECT name FROM products;");

        assertEquals(200, inserted.statusCode(), inserted.body());
        assertEquals("{\"rows\":[{\"name\":\"따옴표\\\"역슬래시\\\\줄\\n😀\"}]}", selected.body());
    }

    @Test
    void 요청의_charset_UTF8과_JSON_유니코드_이스케이프를_받는다() throws Exception {
        startRealServer(2, 16);

        HttpResponse<String> response = post("/sql",
                "{\"sql\":\"INSERT INTO products (name, price) VALUES ('\\uD55C\\uAE00', 1);\"}",
                "application/json; charset=UTF-8");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{\"rows\":[{\"name\":\"한글\"}]}", sql("SELECT name FROM products;").body());
    }

    // ---- 오류 응답 ----

    @Test
    void 엔진_오류는_코드와_상태로_바뀐다() throws Exception {
        startRealServer(2, 16);

        assertError(sql("SELECT * FROM products"), 400, "SQL_SYNTAX");
        assertError(sql("SELECT * FROM products; SELECT * FROM products;"), 400, "SQL_SYNTAX");
        assertError(sql(";"), 400, "SQL_SYNTAX");
        assertError(sql("SELECT * FROM nothing;"), 400, "UNKNOWN_TABLE");
        assertError(sql("SELECT nothing FROM products;"), 400, "UNKNOWN_COLUMN");
        assertError(sql("INSERT INTO products (id, name, price) VALUES (1, 'a', 1);"), 400, "INVALID_VALUE");
        assertError(sql("INSERT INTO products (name, price) VALUES ('a', -1);"), 400, "INVALID_VALUE");
    }

    @Test
    void 요청_형식이_잘못되면_BAD_REQUEST다() throws Exception {
        startRealServer(2, 16);
        String json = "application/json";

        assertError(post("/sql", "", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "not json", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":5}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":null}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":[\"SELECT * FROM products;\"]}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"\"}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"   \"}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"SELECT * FROM products;\",\"extra\":1}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"a\",\"sql\":\"b\"}", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "[\"SELECT * FROM products;\"]", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"SELECT * FROM products;\"} x", json), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"SELECT * FROM products;\"}", "text/plain"), 400, "BAD_REQUEST");
        assertError(post("/sql", "{\"sql\":\"SELECT * FROM products;\"}", "application/json; charset=latin1"),
                400, "BAD_REQUEST");
        assertError(post("/sql", "[".repeat(2000), json), 400, "BAD_REQUEST");
    }

    @Test
    void Content_Type이_틀린_큰_본문도_연결이_끊기지_않고_400을_받는다() throws Exception {
        startRealServer(2, 16);
        String bigBody = "{\"sql\":\"" + "a".repeat(500_000) + "\"}";

        HttpResponse<String> response = post("/sql", bigBody, "text/plain");

        assertError(response, 400, "BAD_REQUEST");
        assertEquals(200, get("/health").statusCode());
    }

    @Test
    void UTF8이_아닌_본문은_BAD_REQUEST다() throws Exception {
        startRealServer(2, 16);
        HttpRequest request = HttpRequest.newBuilder(uri("/sql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {'{', '"', 's', (byte) 0xC3, (byte) 0x28, '}'}))
                .build();

        assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 400, "BAD_REQUEST");
    }

    @Test
    void 없는_경로는_404_허용하지_않는_메서드는_405다() throws Exception {
        startRealServer(2, 16);

        assertError(get("/nothing"), 404, "NOT_FOUND");
        assertError(get("/sql/"), 404, "NOT_FOUND");
        assertError(get("/"), 404, "NOT_FOUND");
        HttpResponse<String> getSql = get("/sql");
        assertError(getSql, 405, "METHOD_NOT_ALLOWED");
        assertEquals("POST", getSql.headers().firstValue("Allow").orElse(""));
        HttpResponse<String> postHealth = post("/health", "{}", "application/json");
        assertError(postHealth, 405, "METHOD_NOT_ALLOWED");
        assertEquals("GET", postHealth.headers().firstValue("Allow").orElse(""));
        HttpRequest delete = HttpRequest.newBuilder(uri("/sql")).DELETE().build();
        assertError(client.send(delete, HttpResponse.BodyHandlers.ofString()), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void 너무_큰_본문은_413이고_서버는_계속_동작한다() throws Exception {
        startRealServer(2, 16);
        String hugeBody = "{\"sql\":\"" + "a".repeat(DbHttpServer.MAX_BODY_BYTES + 500_000) + "\"}";

        HttpResponse<String> response = post("/sql", hugeBody, "application/json");

        assertError(response, 413, "PAYLOAD_TOO_LARGE");
        assertEquals(200, get("/health").statusCode());
    }

    @Test
    void 한도_안의_아주_긴_SQL은_엔진이_오류로_돌려준다() throws Exception {
        startRealServer(2, 16);
        String longValue = "a".repeat(DbHttpServer.MAX_BODY_BYTES - 200);

        HttpResponse<String> response = sql("INSERT INTO products (name, price) VALUES ('" + longValue + "', 1);");

        assertError(response, 400, "INVALID_VALUE");
    }

    @Test
    void 오류_응답에는_내부_경로나_스택이_없다() throws Exception {
        startRealServer(2, 16);

        for (HttpResponse<String> response : List.of(sql("SELECT * FROM nothing;"), sql("bad"),
                post("/sql", "{", "application/json"), get("/nothing"))) {
            assertFalse(response.body().contains(tempDir.toString()), response.body());
            assertFalse(response.body().contains("Exception"), response.body());
            assertFalse(response.body().contains("at "), response.body());
            assertTrue(response.body().matches("\\{\"error\":\"[A-Z_]+\"\\}"), response.body());
        }
    }

    // ---- 워커 풀과 큐 ----

    @Test
    void 큐가_가득_차면_503이고_풀리면_서버가_계속_동작한다() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        startServerWith(1, 1, sqlText -> {
            workerEntered.countDown();
            awaitQuietly(release);
            return DbResult.forInsert(1);
        });

        CompletableFuture<HttpResponse<String>> running = sqlAsync("INSERT INTO products (name, price) VALUES ('a', 1);");
        assertTrue(workerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
        CompletableFuture<HttpResponse<String>> queued = sqlAsync("INSERT INTO products (name, price) VALUES ('b', 1);");
        waitUntil(() -> pool.getQueuedCount() == 1);

        HttpResponse<String> rejected = sql("SELECT * FROM products;");

        assertError(rejected, 503, "SERVER_BUSY");
        release.countDown();
        assertEquals(200, running.get(WAIT_SECONDS, TimeUnit.SECONDS).statusCode());
        assertEquals(200, queued.get(WAIT_SECONDS, TimeUnit.SECONDS).statusCode());
        assertEquals(200, sql("SELECT * FROM products;").statusCode(), "큐가 비면 다시 받아야 한다");
        assertTrue(pool.getRejectedCount() >= 1);
    }

    @Test
    void 실행_중_오류가_난_뒤에도_워커가_살아_있다() throws Exception {
        startServerWith(1, 4, sqlText -> {
            if (sqlText.contains("boom")) {
                throw new IllegalStateException("일부러 낸 오류 /secret/path");
            }
            return DbResult.forInsert(7);
        });

        HttpResponse<String> failed = sql("boom;");
        HttpResponse<String> next = sql("INSERT INTO products (name, price) VALUES ('a', 1);");

        assertError(failed, 500, "INTERNAL_ERROR");
        assertFalse(failed.body().contains("secret"));
        assertEquals("{\"id\":7}", next.body());
    }

    @Test
    void 엔진의_INTERNAL_ERROR는_500이다() throws Exception {
        startServerWith(1, 4, sqlText -> DbResult.forError(ErrorCode.INTERNAL_ERROR, "[x] /secret/path 실패"));

        HttpResponse<String> response = sql("SELECT * FROM products;");

        assertError(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().contains("secret"));
    }

    @Test
    void 두_SELECT가_엔진의_읽기_구간에_함께_들어간다() throws Exception {
        startRealServer(2, 16);
        sql("INSERT INTO products (name, price) VALUES ('a', 1);");
        CyclicBarrier barrier = new CyclicBarrier(2);
        engine.setSelectHook(() -> {
            try {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("읽기 구간에 함께 들어가지 못했습니다", exception);
            }
        });

        CompletableFuture<HttpResponse<String>> first = sqlAsync("SELECT * FROM products;");
        CompletableFuture<HttpResponse<String>> second = sqlAsync("SELECT * FROM products WHERE id = 1;");

        assertEquals(200, first.get(WAIT_SECONDS, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(WAIT_SECONDS, TimeUnit.SECONDS).statusCode());
    }

    // ---- 동시성 ----

    @Test
    void 같은_테이블에_동시_INSERT를_해도_id가_겹치지_않고_빈_번호가_없다() throws Exception {
        startRealServer(4, 128);
        int requestCount = 200;
        ExecutorService clients = Executors.newFixedThreadPool(16);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 1; i <= requestCount; i++) {
                String sqlText = "INSERT INTO products (name, price) VALUES ('item" + i + "', " + i + ");";
                futures.add(clients.submit(() -> sql(sqlText)));
            }

            Set<Integer> ids = new TreeSet<>();
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get(WAIT_SECONDS * 4, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                ids.add(Integer.parseInt(response.body().replaceAll("\\D", "")));
            }

            assertEquals(requestCount, ids.size(), "id가 겹치면 안 된다");
            assertEquals(1, ids.iterator().next());
            assertEquals(requestCount, ((TreeSet<Integer>) ids).last(), "빈 번호가 없어야 한다");
            assertAllRowsConsistent();
        } finally {
            clients.shutdownNow();
        }
    }

    @Test
    void 서버를_다시_시작해도_데이터와_인덱스가_그대로다() throws Exception {
        startRealServer(2, 16);
        for (int i = 1; i <= 50; i++) {
            assertEquals(200, sql("INSERT INTO products (name, price) VALUES ('item" + i + "', " + i + ");").statusCode());
        }
        server.stop();
        engine.close();

        startRealServer(2, 16);

        assertEquals("{\"rows\":[{\"id\":33,\"name\":\"item33\",\"price\":33}]}",
                sql("SELECT * FROM products WHERE id = 33;").body());
        assertEquals("{\"id\":51}", sql("INSERT INTO products (name, price) VALUES ('after', 51);").body());
    }

    @Test
    void 서버를_끄면_더_이상_연결되지_않고_두_번_꺼도_된다() throws Exception {
        startRealServer(2, 16);
        assertEquals(200, get("/health").statusCode());

        server.stop();
        server.stop();

        assertThrows(IOException.class, () -> get("/health"));
    }

    // ---- 도구 ----

    private void assertAllRowsConsistent() throws Exception {
        HttpResponse<String> all = sql("SELECT * FROM products;");
        String body = all.body();
        int rowCount = body.split("\\{\"id\"", -1).length - 1;
        assertEquals(200, rowCount, "조회한 행 수가 INSERT 수와 같아야 한다");
        for (int id = 1; id <= 200; id++) {
            HttpResponse<String> one = sql("SELECT * FROM products WHERE id = " + id + ";");
            assertTrue(one.body().matches("\\{\"rows\":\\[\\{\"id\":" + id + ",\"name\":\"item(\\d+)\",\"price\":\\1\\}\\]\\}"),
                    one.body());
        }
    }

    private void startRealServer(int workers, int queue) throws IOException {
        Path dataDir = tempDir.resolve("data");
        if (!dataDir.toFile().exists()) {
            TestSupport.prepareDataDir(tempDir);
        }
        engine = new DbEngine(dataDir);
        startServerWith(workers, queue, engine::execute);
    }

    private void startServerWith(int workers, int queue, SqlExecutor executor) throws IOException {
        pool = new DbWorkerPool(workers, queue);
        server = new DbHttpServer(executor, pool, new InetSocketAddress("127.0.0.1", 0));
        server.start();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getPort() + path);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(WAIT_SECONDS * 2))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sql(String sqlText) throws IOException, InterruptedException {
        return post("/sql", "{\"sql\":" + JsonWriter.quote(sqlText) + "}", "application/json");
    }

    private CompletableFuture<HttpResponse<String>> sqlAsync(String sqlText) {
        HttpRequest request = HttpRequest.newBuilder(uri("/sql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sql\":" + JsonWriter.quote(sqlText) + "}",
                        StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertError(HttpResponse<String> response, int status, String errorCode) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("{\"error\":\"" + errorCode + "\"}", response.body());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "조건이 시간 안에 참이 되지 않았습니다");
            Thread.sleep(5);
        }
        assertNotNull(condition);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
