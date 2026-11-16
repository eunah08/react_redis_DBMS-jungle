package minidbms.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import minidbms.json.JsonParseException;
import minidbms.json.JsonReader;
import minidbms.json.JsonWriter;
import minidbms.storage.StorageException;

/**
 * HTTP API 서버. JDK HttpServer로 POST /sql, GET /health를 받는다. (협약 9.3, 10.3)
 * 요청·응답을 변환하고 SqlExecutor(운영에서는 DbEngine.execute)만 부른다.
 * storage·index를 직접 부르지 않는다. (협약 6장)
 *
 * <p>응답 규칙 (README 10.3에도 적는다) :
 * 없는 경로 404 {"error":"NOT_FOUND"}, 허용하지 않는 메서드 405 {"error":"METHOD_NOT_ALLOWED"} + Allow 헤더,
 * 본문이 1MiB를 넘으면 413 {"error":"PAYLOAD_TOO_LARGE"}, Content-Type이 application/json이 아니면
 * 400 BAD_REQUEST. 오류 응답에는 오류 코드만 담고 내부 경로·스택은 담지 않는다. 자세한 원인은 서버 로그에만 남긴다.
 */
public final class DbHttpServer {

    /** 요청 본문 최대 바이트 수. */
    public static final int MAX_BODY_BYTES = 1 << 20;

    private static final long MAX_DISCARD_BYTES = 8L << 20;
    private static final int DISCARD_BUFFER_BYTES = 8192;
    private static final String HOST_DEFAULT = "127.0.0.1";
    private static final int PORT_DEFAULT = 8080;
    private static final String DATA_DIR_DEFAULT = "data";
    private static final int STOP_DELAY_SECONDS = 1;
    private static final int TERMINATION_WAIT_SECONDS = 60;
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String SQL_FIELD = "sql";

    private final SqlExecutor executor;
    private final DbWorkerPool pool;
    private final HttpServer httpServer;
    private final AtomicBoolean isStopped = new AtomicBoolean();

    /**
     * 서버를 만들고 주소에 묶는다. 요청 처리는 pool이 맡는다. start()를 불러야 요청을 받는다.
     *
     * @param executor SQL 실행 길 (운영에서는 DbEngine::execute)
     * @param pool     요청을 처리할 스레드 풀. stop()이 함께 종료한다
     * @param host     묶을 주소
     * @param port     묶을 포트. 0이면 빈 포트를 고른다
     * @throws IOException 주소에 묶지 못한 경우
     */
    public DbHttpServer(SqlExecutor executor, DbWorkerPool pool, String host, int port) throws IOException {
        this.executor = executor;
        this.pool = pool;
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.httpServer.setExecutor(pool);
        this.httpServer.createContext("/", this::handle);
    }

    /**
     * 요청 수신을 시작한다.
     */
    public void start() {
        httpServer.start();
    }

    /**
     * 서버를 끈다. 새 요청 수신을 멈추고, 큐에 남은 작업과 실행 중인 작업을 마친 뒤 워커를 종료한다.
     * 엔진은 닫지 않는다. 엔진을 연 쪽이 그 뒤에 닫는다. (협약 9.3) 여러 번 불러도 된다.
     */
    public void stop() {
        if (!isStopped.compareAndSet(false, true)) {
            return;
        }
        httpServer.stop(STOP_DELAY_SECONDS);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("[stop] 워커가 " + TERMINATION_WAIT_SECONDS + "초 안에 끝나지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return 실제로 묶인 포트
     */
    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    /**
     * server 명령을 실행한다. 종료 신호(Ctrl+C)를 받으면 서버, 워커, 엔진 순서로 정리한다.
     *
     * @param args "server" 뒤의 인자. --host --port --workers --queue --data-dir
     * @return 정상 종료하면 0, 시작에 실패하면 1
     */
    public static int run(String[] args) {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("사용법: java -jar target/mini-dbms.jar server [--host 127.0.0.1] [--port 8080]"
                    + " [--workers N] [--queue 128] [--data-dir data]");
            return 1;
        }
        DbEngine engine;
        try {
            engine = new DbEngine(options.dataDir());
        } catch (StorageException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        return serve(options, engine);
    }

    private static int serve(ServerOptions options, DbEngine engine) {
        DbWorkerPool pool = new DbWorkerPool(options.workers(), options.queue());
        DbHttpServer server;
        try {
            server = new DbHttpServer(engine::execute, pool, options.host(), options.port());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[run] 서버를 시작하지 못했습니다: " + e.getMessage());
            pool.shutdown();
            engine.close();
            return 1;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            engine.close();
            stopped.countDown();
        }, "db-shutdown"));
        server.start();
        System.out.println("서버 시작: http://" + options.host() + ":" + server.getPort()
                + " (워커 " + options.workers() + ", 큐 " + options.queue() + ", 데이터 " + options.dataDir() + ")");
        awaitQuietly(stopped);
        return 0;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            Reply reply = createReply(exchange);
            send(exchange, reply);
        } catch (IOException e) {
            System.err.println("[handle] 응답을 보내지 못했습니다 (클라이언트가 연결을 끊었을 수 있습니다): " + e.getMessage());
        } catch (RuntimeException | Error e) {
            System.err.println("[handle] 요청 처리 중 오류: " + e);
            sendInternalErrorQuietly(exchange);
        } finally {
            exchange.close();
        }
    }

    private Reply createReply(HttpExchange exchange) throws IOException {
        if (pool.isRejectingCurrentTask()) {
            return Reply.error(503, ErrorCode.SERVER_BUSY.name()).closing();
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/health")) {
            return method.equals("GET") ? new Reply(200, "{\"status\":\"ok\"}", null, false) : methodNotAllowed("GET");
        }
        if (path.equals("/sql")) {
            return method.equals("POST") ? createSqlReply(exchange) : methodNotAllowed("POST");
        }
        return Reply.error(404, "NOT_FOUND");
    }

    private Reply createSqlReply(HttpExchange exchange) throws IOException {
        if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            return badRequest("Content-Type은 application/json(UTF-8)이어야 합니다");
        }
        String declaredLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declaredLength != null && isLargerThanLimit(declaredLength)) {
            discardUpTo(exchange.getRequestBody(), MAX_DISCARD_BYTES);
            return Reply.error(413, "PAYLOAD_TOO_LARGE").closing();
        }
        byte[] body = readBody(exchange.getRequestBody());
        if (body == null) {
            return Reply.error(413, "PAYLOAD_TOO_LARGE").closing();
        }
        String sqlText = extractSql(body);
        if (sqlText == null) {
            return badRequest("본문은 문자열 필드 sql 하나만 가진 JSON 객체여야 합니다");
        }
        return toReply(executor.execute(sqlText));
    }

    private static Reply toReply(DbResult result) {
        if (!result.isError()) {
            return new Reply(200, JsonWriter.toJson(result), null, false);
        }
        ErrorCode errorCode = result.getErrorCode();
        if (errorCode == ErrorCode.INTERNAL_ERROR) {
            System.err.println("[execute] 내부 오류: " + result.getMessage());
        }
        return Reply.error(errorCode.getHttpStatus(), errorCode.name());
    }

    /** 본문에서 sql 문자열을 꺼낸다. 규칙에 맞지 않으면 null (BAD_REQUEST). */
    private static String extractSql(byte[] body) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            Object parsed = JsonReader.parse(text);
            if (!(parsed instanceof Map<?, ?> members) || members.size() != 1
                    || !(members.get(SQL_FIELD) instanceof String sqlText) || sqlText.isBlank()) {
                return null;
            }
            return sqlText;
        } catch (CharacterCodingException | JsonParseException e) {
            return null;
        }
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";");
        if (!parts[0].trim().equalsIgnoreCase("application/json")) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim().toLowerCase(Locale.ROOT).replace("\"", "");
            if (parameter.startsWith("charset=") && !parameter.equals("charset=utf-8")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLargerThanLimit(String declaredLength) {
        try {
            return Long.parseLong(declaredLength.trim()) > MAX_BODY_BYTES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 너무 큰 본문을 버리며 읽어 둔다. 클라이언트가 보내기를 마쳐야 413 응답을 읽을 수 있기 때문이다.
     * 한도를 넘는 만큼은 읽지 않고 연결을 닫는다.
     */
    private static void discardUpTo(InputStream input, long maxBytes) throws IOException {
        byte[] buffer = new byte[DISCARD_BUFFER_BYTES];
        long total = 0;
        while (total < maxBytes) {
            int readBytes = input.read(buffer);
            if (readBytes < 0) {
                return;
            }
            total += readBytes;
        }
    }

    /** 한도까지만 읽는다. 한도를 넘으면 null. */
    private static byte[] readBody(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static Reply badRequest(String reason) {
        System.err.println("[handle] BAD_REQUEST: " + reason);
        return Reply.error(400, ErrorCode.BAD_REQUEST.name());
    }

    private static Reply methodNotAllowed(String allowedMethod) {
        return new Reply(405, JsonWriter.errorToJson("METHOD_NOT_ALLOWED"), allowedMethod, false);
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        if (reply.allowedMethod() != null) {
            exchange.getResponseHeaders().set("Allow", reply.allowedMethod());
        }
        if (reply.isConnectionClosed()) {
            exchange.getResponseHeaders().set("Connection", "close");
        }
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendInternalErrorQuietly(HttpExchange exchange) {
        try {
            send(exchange, Reply.error(500, ErrorCode.INTERNAL_ERROR.name()));
        } catch (IOException | RuntimeException e) {
            System.err.println("[handle] 오류 응답도 보내지 못했습니다: " + e.getMessage());
        }
    }

    /** 보낼 응답. */
    private record Reply(int status, String body, String allowedMethod, boolean isConnectionClosed) {

        static Reply error(int status, String errorCode) {
            return new Reply(status, JsonWriter.errorToJson(errorCode), null, false);
        }

        Reply closing() {
            return new Reply(status, body, allowedMethod, true);
        }
    }

    /** server 명령의 옵션. */
    private record ServerOptions(String host, int port, int workers, int queue, Path dataDir) {

        static ServerOptions parse(String[] args) {
            String host = HOST_DEFAULT;
            int port = PORT_DEFAULT;
            int workers = Runtime.getRuntime().availableProcessors();
            int queue = DbWorkerPool.DEFAULT_QUEUE_CAPACITY;
            Path dataDir = Path.of(DATA_DIR_DEFAULT);
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("[parse] " + args[i] + " 뒤에 값이 필요합니다");
                }
                switch (args[i]) {
                    case "--host" -> host = args[i + 1];
                    case "--port" -> port = toNumber(args[i], args[i + 1], 0, 65535);
                    case "--workers" -> workers = toNumber(args[i], args[i + 1], 1, 1024);
                    case "--queue" -> queue = toNumber(args[i], args[i + 1], 1, 1_000_000);
                    case "--data-dir" -> dataDir = Path.of(args[i + 1]);
                    default -> throw new IllegalArgumentException("[parse] 알 수 없는 옵션입니다: " + args[i]);
                }
            }
            return new ServerOptions(host, port, workers, queue, dataDir);
        }

        private static int toNumber(String option, String value, int min, int max) {
            try {
                int number = Integer.parseInt(value);
                if (number >= min && number <= max) {
                    return number;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[parse] " + option + " 값이 숫자가 아닙니다: " + value);
            }
            throw new IllegalArgumentException("[parse] " + option + " 값은 " + min + "~" + max + " 사이여야 합니다");
        }
    }
}
