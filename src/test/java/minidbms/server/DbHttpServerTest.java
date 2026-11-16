package minidbms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 10.2~10.4, 11.3 : HTTP 경로, 상태 코드, 동시 요청, 재시작, 큐 초과(503) 검증.
 */
class DbHttpServerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static DbHttpServer startServer(Path dataDir, int workers, int queue) throws IOException {
        if (!Files.exists(dataDir.resolve("products.schema"))) {
            Files.writeString(dataDir.resolve("products.schema"), "name string 32\nprice int\n");
        }
        DbHttpServer server = new DbHttpServer(new ServerOptions("127.0.0.1", 0, workers, queue, dataDir));
        server.start();
        return server;
    }

    private static URI uri(DbHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getPort() + path);
    }

    private static HttpResponse<String> post(DbHttpServer server, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, "/sql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String sqlBody(String sql) {
        return "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    @Test
    void health는_200이다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 2, 8);
        try {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(uri(server, "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void sql을_보내면_JSON으로_답한다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 2, 8);
        try {
            HttpResponse<String> insert = post(server, sqlBody("INSERT INTO products (name, price) VALUES ('키보드', 35000);"));
            HttpResponse<String> select = post(server, sqlBody("SELECT * FROM products WHERE id = 1;"));

            assertEquals(200, insert.statusCode());
            assertEquals("{\"id\":1}", insert.body());
            assertEquals(200, select.statusCode());
            assertEquals("{\"rows\":[{\"id\":1,\"name\":\"키보드\",\"price\":35000}]}", select.body());
            assertTrue(select.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        } finally {
            server.stop();
        }
    }

    @Test
    void 오류는_협약의_상태_코드와_이름으로_답한다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 2, 8);
        try {
            assertStatus(server, "SELEC * FROM products;", 400, "SQL_SYNTAX");
            assertStatus(server, "SELECT * FROM nothing;", 400, "UNKNOWN_TABLE");
            assertStatus(server, "SELECT color FROM products;", 400, "UNKNOWN_COLUMN");
            assertStatus(server, "INSERT INTO products (name, price) VALUES ('a', -1);", 400, "INVALID_VALUE");
        } finally {
            server.stop();
        }
    }

    private static void assertStatus(DbHttpServer server, String sql, int status, String error) throws Exception {
        HttpResponse<String> response = post(server, sqlBody(sql));
        assertEquals(status, response.statusCode(), sql);
        assertEquals("{\"error\":\"" + error + "\"}", response.body());
    }

    @Test
    void 잘못된_요청은_400_404_405_413이다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 2, 8);
        try {
            for (String body : List.of("", "not json", "{}", "{\"sql\":1}", "{\"sql\":\"   \"}")) {
                HttpResponse<String> response = post(server, body);
                assertEquals(400, response.statusCode(), body);
                assertEquals("{\"error\":\"BAD_REQUEST\"}", response.body());
            }
            HttpResponse<String> notFound = CLIENT.send(HttpRequest.newBuilder(uri(server, "/nope")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> getSql = CLIENT.send(HttpRequest.newBuilder(uri(server, "/sql")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> postHealth = CLIENT.send(HttpRequest.newBuilder(uri(server, "/health"))
                    .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> tooBig = post(server, sqlBody("SELECT " + "x".repeat(1024 * 1024 + 10)));

            assertEquals(404, notFound.statusCode());
            assertEquals(405, getSql.statusCode());
            assertEquals("POST", getSql.headers().firstValue("Allow").orElse(""));
            assertEquals(405, postHealth.statusCode());
            assertEquals(413, tooBig.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void 동시_INSERT_요청도_id가_겹치지_않는다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 4, 256);
        ExecutorService clients = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Callable<String> job = () -> post(server, sqlBody("INSERT INTO products (name, price) VALUES ('x', 1);")).body();
                futures.add(clients.submit(job));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<String> future : futures) {
                bodies.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(100, bodies.size());
            HttpResponse<String> all = post(server, sqlBody("SELECT * FROM products;"));
            assertEquals(100, all.body().split("\\{\"id\"", -1).length - 1);
        } finally {
            clients.shutdownNow();
            server.stop();
        }
    }

    @Test
    void 서버를_껐다_켜도_데이터가_남는다(@TempDir Path dataDir) throws Exception {
        DbHttpServer first = startServer(dataDir, 2, 8);
        post(first, sqlBody("INSERT INTO products (name, price) VALUES ('a', 1);"));
        first.stop();

        DbHttpServer second = startServer(dataDir, 2, 8);
        try {
            HttpResponse<String> insert = post(second, sqlBody("INSERT INTO products (name, price) VALUES ('b', 2);"));
            HttpResponse<String> select = post(second, sqlBody("SELECT * FROM products WHERE id = 1;"));

            assertEquals("{\"id\":2}", insert.body());
            assertEquals("{\"rows\":[{\"id\":1,\"name\":\"a\",\"price\":1}]}", select.body());
        } finally {
            second.stop();
        }
    }

    @Test
    void 워커와_큐가_모두_차면_503_SERVER_BUSY다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 1, 1);
        List<Socket> blockers = new ArrayList<>();
        try {
            // 요청 본문을 다 보내지 않으면 워커가 본문을 기다리며 묶인다. 첫 번째는 워커, 두 번째는 큐를 차지한다.
            for (int i = 0; i < 2; i++) {
                blockers.add(openPartialRequest(server.getPort()));
                Thread.sleep(300);
            }

            HttpResponse<String> response = post(server, sqlBody("SELECT * FROM products;"));

            assertEquals(503, response.statusCode());
            assertEquals("{\"error\":\"SERVER_BUSY\"}", response.body());
        } finally {
            for (Socket socket : blockers) {
                socket.close();
            }
            server.stop();
        }
    }

    @Test
    void 큐가_찬_뒤_헤더를_덜_보낸_연결이_있어도_새_요청은_503을_받는다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 1, 1);
        List<Socket> blockers = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                blockers.add(openPartialRequest(server.getPort()));
                Thread.sleep(300);
            }
            // 큐가 찬 뒤 들어온 연결이 헤더를 끝내지 않는다. 수신 스레드가 이 연결을 기다리면 아래 요청이 멈춘다.
            Socket slowHeader = new Socket(InetAddress.getLoopbackAddress(), server.getPort());
            blockers.add(slowHeader);
            slowHeader.getOutputStream().write("POST /sql HTTP/1.1\r\nHost: local".getBytes(StandardCharsets.UTF_8));
            slowHeader.getOutputStream().flush();
            Thread.sleep(300);

            HttpRequest request = HttpRequest.newBuilder(uri(server, "/sql"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sqlBody("SELECT * FROM products;"))).build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
        } finally {
            for (Socket socket : blockers) {
                socket.close();
            }
            server.stop();
        }
    }

    private static Socket openPartialRequest(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        OutputStream out = socket.getOutputStream();
        out.write(("POST /sql HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n{\"sql\":")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        return socket;
    }

    @Test
    void 클라이언트가_중간에_끊어도_서버는_계속_답한다(@TempDir Path dataDir) throws Exception {
        DbHttpServer server = startServer(dataDir, 2, 8);
        try {
            openPartialRequest(server.getPort()).close();
            Thread.sleep(200);

            HttpResponse<String> response = post(server, sqlBody("INSERT INTO products (name, price) VALUES ('a', 1);"));

            assertEquals(200, response.statusCode());
        } finally {
            server.stop();
        }
    }
}
