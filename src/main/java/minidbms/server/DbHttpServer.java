package minidbms.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import minidbms.json.JsonFormatException;
import minidbms.json.JsonReader;
import minidbms.json.JsonWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * HTTP API 서버. JDK HttpServer로 POST /sql, GET /health를 받는다. (협약 9.3, 10.3)
 * 요청·응답을 변환하고 DbEngine.execute만 부른다. storage·index를 직접 부르지 않는다. (협약 6장)
 *
 * <p>요청 흐름 : HttpServer → DbWorkerPool 작업 큐 → 워커 스레드 → DbEngine.execute → JSON 응답.
 * 큐가 가득 차면 풀이 거절 처리기를 호출한다. 처리기는 요청 처리 작업을 호출한 스레드에서 "거절 표시"를 달고 실행하고,
 * 핸들러는 그 표시를 보고 엔진을 부르지 않은 채 503 SERVER_BUSY를 바로 보낸다.
 *
 * <p>응답 규칙 (README에도 적는다) : 없는 경로 404 NOT_FOUND, 허용하지 않는 메서드 405 METHOD_NOT_ALLOWED
 * (Allow 헤더 포함), 본문이 1MiB를 넘으면 413 PAYLOAD_TOO_LARGE, 잘못된 JSON·UTF-8은 400 BAD_REQUEST.
 * Content-Type 헤더는 검사하지 않는다. 오류 응답에는 내부 경로나 스택을 담지 않는다.
 */
public final class DbHttpServer {

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final long STOP_WAIT_MILLIS = 10_000;
    private static final int STOP_DELAY_SECONDS = 1;
    private static final String JSON_TYPE = "application/json; charset=utf-8";

    /** 큐가 가득 차서 거절된 요청을 실행하는 동안만 true인 표시. */
    private static final ThreadLocal<Boolean> IS_REJECTED = new ThreadLocal<>();

    private final DbEngine engine;
    private final DbWorkerPool pool;
    private final HttpServer httpServer;

    /**
     * 서버를 만든다. 아직 요청을 받지 않는다. start()를 불러야 시작한다.
     *
     * @param options 서버 옵션
     * @throws IOException 포트를 열 수 없을 때
     */
    public DbHttpServer(ServerOptions options) throws IOException {
        this.engine = new DbEngine(options.dataDir());
        this.pool = new DbWorkerPool(options.workers(), options.queue(), DbHttpServer::runAsRejected);
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
        } catch (IOException | RuntimeException e) {
            pool.shutdown();
            engine.close();
            throw e;
        }
        httpServer.createContext("/", this::handle);
        httpServer.setExecutor(pool);
    }

    /**
     * server 명령을 실행한다. 종료 신호(Ctrl+C)가 올 때까지 돌아오지 않는다.
     *
     * @param args "server" 뒤의 인자. --host --port --workers --queue --data-dir
     * @return 정상 종료하면 0, 시작에 실패하면 1
     */
    public static int run(String[] args) {
        try {
            ServerOptions options = ServerOptions.parse(args);
            DbHttpServer server = new DbHttpServer(options);
            server.start();
            System.out.println("서버 시작 : http://" + options.host() + ":" + server.getPort()
                    + " (워커 " + options.workers() + "개, 큐 " + options.queue() + ")");
            return waitUntilStopped(server);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("[run] 서버를 시작하지 못했습니다: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 요청 받기를 시작한다.
     */
    public void start() {
        httpServer.start();
    }

    /**
     * 서버를 끈다. 새 요청 수신을 중단하고, 큐에 남은 작업과 실행 중인 작업을 마친 뒤 워커를 종료하고,
     * 마지막으로 엔진의 파일 자원을 닫는다. (협약 9.3)
     */
    public void stop() {
        httpServer.stop(STOP_DELAY_SECONDS);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(STOP_WAIT_MILLIS)) {
                System.err.println("[stop] 일부 워커가 제한 시간 안에 끝나지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        engine.close();
    }

    /**
     * @return 실제로 열린 포트 (옵션 포트가 0이면 운영체제가 고른 값)
     */
    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    private static int waitUntilStopped(DbHttpServer server) {
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stopped.countDown();
        }));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static void runAsRejected(Runnable task) {
        IS_REJECTED.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            IS_REJECTED.remove();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (Boolean.TRUE.equals(IS_REJECTED.get())) {
                sendError(exchange, ErrorCode.SERVER_BUSY);
            } else {
                route(exchange);
            }
        } catch (RuntimeException e) {
            sendError(exchange, ErrorCode.INTERNAL_ERROR);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) {
            handleHealth(exchange);
        } else if (path.equals("/sql")) {
            handleSql(exchange);
        } else {
            sendJson(exchange, 404, JsonWriter.toErrorJson("NOT_FOUND"));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleSql(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            sendJson(exchange, 413, JsonWriter.toErrorJson("PAYLOAD_TOO_LARGE"));
            return;
        }
        String sqlText = readSqlText(body);
        if (sqlText == null) {
            sendError(exchange, ErrorCode.BAD_REQUEST);
            return;
        }
        DbResult result = engine.execute(sqlText);
        int status = result.isError() ? result.getErrorCode().getHttpStatus() : 200;
        sendJson(exchange, status, JsonWriter.toJson(result));
    }

    /** 본문에서 sql을 꺼낸다. 잘못된 UTF-8·JSON이거나 빈 SQL이면 null. */
    private static String readSqlText(byte[] body) {
        try {
            String bodyText = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            String sqlText = JsonReader.readSqlRequest(bodyText);
            return sqlText.isBlank() ? null : sqlText;
        } catch (CharacterCodingException | JsonFormatException e) {
            return null;
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethod);
        sendJson(exchange, 405, JsonWriter.toErrorJson("METHOD_NOT_ALLOWED"));
    }

    private void sendError(HttpExchange exchange, ErrorCode errorCode) throws IOException {
        sendJson(exchange, errorCode.getHttpStatus(), JsonWriter.toErrorJson(errorCode.name()));
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
