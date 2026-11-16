package minidbms.server;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidbms.JsonCodec;
import minidbms.engine.DbEngine;

class DbHttpServerTest {
    @TempDir Path dataDir;
    private DbEngine engine;
    private DbHttpServer server;
    private HttpClient client;
    private static final String INSERT = "INSERT INTO products (name, price) VALUES ('http', 7);";

    @BeforeEach void start() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
        engine = new DbEngine(dataDir);
        server = DbHttpServer.start(engine, new InetSocketAddress("127.0.0.1", 0), new DbHttpServer.PoolOptions(2, 128));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach void stop() {
        if (server != null) { server.close(); }
        if (client != null) { client.close(); }
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private String sqlBody(String sqlText) { return JsonCodec.toJson(Map.of("sql", sqlText)); }

    @Test
    void actualHttpSharesEngineAndPersistentFiles() throws Exception {
        assertEquals("{\"status\":\"ok\"}", call("GET", "/health", null).body());
        var inserted = call("POST", "/sql", sqlBody(INSERT));
        assertEquals(200, inserted.statusCode()); assertEquals("{\"id\":1}", inserted.body());
        var selected = call("POST", "/sql", sqlBody("SELECT * FROM products WHERE id = 1;"));
        assertEquals("{\"rows\":[{\"id\":1,\"name\":\"http\",\"price\":7}]}", selected.body());
        assertEquals("application/json; charset=utf-8", selected.headers().firstValue("Content-Type").orElseThrow());
        server.close();
        engine = new DbEngine(dataDir);
        server = DbHttpServer.start(engine, new InetSocketAddress("127.0.0.1", 0), new DbHttpServer.PoolOptions(2, 128));
        assertEquals(selected.body(), call("POST", "/sql", sqlBody("SELECT * FROM products WHERE id = 1;")).body());
    }

    @Test
    void jsonAndSqlErrorsHaveContractCodesAndPreserveExistingRows() throws Exception {
        call("POST", "/sql", sqlBody(INSERT));
        String[] bodies = {"", "{", "[]", "{}", "{\"sql\":null}", "{\"sql\":1}",
                "{\"sql\":\" \"}", "{\"sql\":\"a\",\"extra\":1}",
                "{\"sql\":\"a\",\"s\\u0071l\":\"b\"}"};
        for (String body : bodies) {
            var response = call("POST", "/sql", body);
            assertEquals(400, response.statusCode(), body);
            assertEquals("{\"error\":\"BAD_REQUEST\"}", response.body(), body);
        }
        assertEquals("{\"error\":\"SQL_SYNTAX\"}", call("POST", "/sql", sqlBody("SELECT * FROM products; SELECT * FROM products;")).body());
        assertEquals("{\"error\":\"UNKNOWN_TABLE\"}", call("POST", "/sql", sqlBody("SELECT * FROM missing;")).body());
        assertEquals("{\"error\":\"INVALID_VALUE\"}", call("POST", "/sql", sqlBody("INSERT INTO products (id) VALUES (3);")).body());
        assertEquals(1, engine.execute("SELECT * FROM products;").getRows().size());
        assertEquals(404, call("GET", "/missing", null).statusCode());
        assertEquals(405, call("GET", "/sql", null).statusCode());
    }

    @Test
    void concurrentHttpInsertsReturnUniqueConsecutiveIdsAndCompleteRows() throws Exception {
        var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int index = 0; index < 80; index++) {
            futures.add(client.sendAsync(request("POST", "/sql", sqlBody(INSERT)), HttpResponse.BodyHandlers.ofString()));
        }
        var ids = new HashSet<Integer>();
        for (var future : futures) {
            var response = future.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            ids.add(((java.math.BigDecimal) ((Map<?, ?>) JsonCodec.parse(response.body())).get("id")).intValueExact());
        }
        assertEquals(new HashSet<>(java.util.stream.IntStream.rangeClosed(1, 80).boxed().toList()), ids);
        assertEquals(80, engine.execute("SELECT * FROM products;").getRows().size());
    }

    private record BlockedWork(ReentrantReadWriteLock lock,
                               java.util.List<CompletableFuture<HttpResponse<String>>> accepted) { }

    @Test
    void fullQueueReturns503AndAcceptedRequestsStillComplete() throws Exception {
        BlockedWork work = saturate();
        try {
            var rejected = call("POST", "/sql", sqlBody(INSERT));
            assertEquals(503, rejected.statusCode());
            assertEquals("{\"error\":\"SERVER_BUSY\"}", rejected.body());
        } finally { work.lock().writeLock().unlock(); }
        awaitAccepted(work);
        assertEquals(200, call("GET", "/health", null).statusCode());
        assertEquals(3, engine.execute("SELECT * FROM products;").getRows().size());
    }

    @Test
    void slowRejectedHeadersDoNotBlockDispatcherWhenNormalWorkersBecomeFree() throws Exception {
        BlockedWork work = saturate();
        try (var slow = partialHeader()) {
            await(() -> slow.isConnected() && parserThreadCount() >= 1);
            work.lock().writeLock().unlock();
            awaitAccepted(work);
            HttpRequest health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.getPort() + "/health")).timeout(Duration.ofMillis(700)).GET().build();
            assertEquals(200, client.send(health, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            if (work.lock().isWriteLockedByCurrentThread()) { work.lock().writeLock().unlock(); }
        }
    }

    @Test
    void saturatedRejectionServiceDoesNotStopAcceptingOrLeakOnShutdown() throws Exception {
        BlockedWork work = saturate();
        java.util.List<java.net.Socket> slow = java.util.List.of();
        try {
            slow = fillRejectionService();
            try (var refused = new java.net.Socket("127.0.0.1", server.getPort())) {
                refused.setSoTimeout(700);
                refused.getOutputStream().write("GET /health HTTP/1.1\r\nHost: local\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try { assertEquals(-1, refused.getInputStream().read()); }
                catch (java.net.SocketException reset) { assertTrue(refused.isConnected()); }
            }
            work.lock().writeLock().unlock();
            awaitAccepted(work);
            assertEquals(200, call("GET", "/health", null).statusCode());
        } finally {
            if (work.lock().isWriteLockedByCurrentThread()) { work.lock().writeLock().unlock(); }
            for (var socket : slow) { socket.close(); }
        }
    }

    private java.util.List<java.net.Socket> fillRejectionService() throws Exception {
        var sockets = new ArrayList<java.net.Socket>();
        try {
            sockets.add(partialHeader()); sockets.add(partialHeader());
            await(() -> parserThreadCount() >= 2);
            for (int index = 0; index < 16; index++) { sockets.add(partialHeader()); }
            await(() -> ((BlockingQueue<?>) fieldUnchecked(fieldUnchecked(server, "rejectedWorkers"), "tasks")).size() == 16);
            return sockets;
        } catch (Exception | Error failure) {
            for (var socket : sockets) { socket.close(); }
            throw failure;
        }
    }

    @Test
    void twoHttpSelectsEnterEngineReadSectionTogether() throws Exception {
        engine.execute(INSERT);
        var readers = new java.util.concurrent.CountDownLatch(2);
        Object storage = field(engine, "tableFile");
        Object rowFile = ((Map<?, ?>) field(storage, "files")).get("products");
        Field channel = rowFile.getClass().getDeclaredField("channel"); channel.setAccessible(true);
        var original = (java.nio.channels.FileChannel) channel.get(rowFile);
        channel.set(rowFile, new minidbms.storage.ChannelDecorator(original) {
            @Override public int read(java.nio.ByteBuffer target, long position) throws java.io.IOException {
                readers.countDown();
                try {
                    if (!readers.await(2, TimeUnit.SECONDS)) { throw new java.io.IOException("readers serialized"); }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new java.io.IOException(failure);
                }
                return delegate.read(target, position);
            }
        });
        var first = client.sendAsync(request("POST", "/sql", sqlBody("SELECT * FROM products WHERE id = 1;")),
                HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("POST", "/sql", sqlBody("SELECT * FROM products WHERE id = 1;")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.get(4, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(4, TimeUnit.SECONDS).statusCode());
        assertEquals(0, readers.getCount());
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void shutdownCompletesAcceptedInsertsAndClosesBothPools() throws Exception {
        BlockedWork work = saturate();
        Thread closer = new Thread(server::close);
        closer.start();
        try {
            await(() -> ((java.util.concurrent.atomic.AtomicBoolean) fieldUnchecked(server, "isClosed")).get());
        } finally { work.lock().writeLock().unlock(); }
        awaitAccepted(work);
        closer.join(4000); assertFalse(closer.isAlive());
        assertStopped("workers"); assertStopped("rejectedWorkers");
        try (DbEngine reopened = new DbEngine(dataDir)) {
            assertEquals(3, reopened.execute("SELECT * FROM products;").getRows().size());
            assertEquals(4, reopened.execute(INSERT).getId());
        }
    }

    private void assertStopped(String poolName) throws Exception {
        Thread[] threads = (Thread[]) field(field(server, poolName), "workers");
        for (Thread thread : threads) { assertFalse(thread.isAlive()); }
    }

    private BlockedWork saturate() throws Exception {
        server.close(); engine = new DbEngine(dataDir);
        server = DbHttpServer.start(engine, new InetSocketAddress("127.0.0.1", 0), new DbHttpServer.PoolOptions(2, 1));
        Map<?, ?> tables = (Map<?, ?>) field(engine, "tables");
        ReentrantReadWriteLock lock = (ReentrantReadWriteLock) field(tables.get("products"), "lock");
        lock.writeLock().lock();
        var accepted = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        try {
            for (int index = 0; index < 2; index++) {
                accepted.add(client.sendAsync(request("POST", "/sql", sqlBody(INSERT)), HttpResponse.BodyHandlers.ofString()));
                int expected = index + 1;
                await(() -> lock.getQueueLength() == expected);
            }
            accepted.add(client.sendAsync(request("POST", "/sql", sqlBody(INSERT)), HttpResponse.BodyHandlers.ofString()));
            await(() -> ((BlockingQueue<?>) fieldUnchecked(fieldUnchecked(server, "workers"), "tasks")).size() == 1);
            return new BlockedWork(lock, accepted);
        } catch (Exception | Error failure) { lock.writeLock().unlock(); throw failure; }
    }

    private void awaitAccepted(BlockedWork work) throws Exception {
        for (var future : work.accepted()) { assertEquals(200, future.get(5, TimeUnit.SECONDS).statusCode()); }
    }

    private java.net.Socket partialHeader() throws Exception {
        var socket = new java.net.Socket("127.0.0.1", server.getPort());
        socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost:"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return socket;
    }

    private long parserThreadCount() {
        return Thread.getAllStackTraces().values().stream().filter(stack -> java.util.Arrays.stream(stack)
                .anyMatch(frame -> frame.getClassName().equals("sun.net.httpserver.Request"))).count();
    }

    @Test
    void oversizedLengthIsRejectedBeforeReadingBody() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("POST /sql HTTP/1.1\r\nHost: local\r\nContent-Length: "
                    + (DbHttpServer.MAX_BODY_BYTES + 1) + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(input.readLine().startsWith("HTTP/1.1 413"));
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) { assertFalse(line.contains("Exception")); }
        }
        assertTrue(engine.execute("SELECT * FROM products;").getRows().isEmpty());
    }

    @Test
    void escapedSqlAndDisconnectedClientDoNotKillWorkers() throws Exception {
        String name = "it's\"\\\n😀";
        String sqlText = "INSERT INTO products (name, price) VALUES ('" + name.replace("'", "''") + "', 1);";
        assertEquals(200, call("POST", "/sql", sqlBody(sqlText)).statusCode());
        Object parsed = JsonCodec.parse(call("POST", "/sql", sqlBody("SELECT name FROM products WHERE id = 1;")).body());
        assertEquals(name, ((Map<?, ?>) ((java.util.List<?>) ((Map<?, ?>) parsed).get("rows")).get(0)).get("name"));
        try (var socket = new java.net.Socket("127.0.0.1", server.getPort())) {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: local\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(200, call("GET", "/health", null).statusCode());
        assertEquals(200, call("POST", "/sql", sqlBody(INSERT)).statusCode());
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private Object fieldUnchecked(Object target, String name) {
        try { return field(target, name); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < deadline); Thread.sleep(5); }
    }
}
