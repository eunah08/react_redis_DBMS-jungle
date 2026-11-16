package minidbms.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import minidbms.JsonCodec;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;

/** HTTP 변환만 수행한다. 파일/인덱스는 직접 접근하지 않고 공유 엔진의 execute를 호출한다. */
public final class DbHttpServer implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 1048576;
    private static final int REJECTION_WORKERS = 2;
    private static final int REJECTION_QUEUE_CAPACITY = 16;
    private static final int BACKLOG = 128;
    private static final int SHUTDOWN_GRACE_SECONDS = 10;
    private final HttpServer http;
    private final DbWorkerPool workers;
    private final DbWorkerPool rejectedWorkers;
    private final DbEngine engine;
    private final ThreadLocal<Boolean> isRejected = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** 워커 수와 큐 크기. 둘 다 양수여야 한다. */
    public record PoolOptions(int workerCount, int queueCapacity) {
        public PoolOptions {
            if (workerCount < 1 || queueCapacity < 1) { throw new IllegalArgumentException("[PoolOptions] 양수가 필요합니다"); }
        }
    }

    private static final class RequestFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private RequestFailure(int status) { this.status = status; }
    }

    private DbHttpServer(DbEngine engine, InetSocketAddress address, PoolOptions options) throws IOException {
        configureTimeouts();
        this.engine = engine;
        http = HttpServer.create(address, BACKLOG);
        rejectedWorkers = new DbWorkerPool(REJECTION_WORKERS, REJECTION_QUEUE_CAPACITY);
        workers = new DbWorkerPool(options.workerCount(), options.queueCapacity(),
                task -> rejectedWorkers.execute(() -> runRejected(task)));
        try {
            http.createContext("/", this::handle);
            http.setExecutor(workers);
            http.start();
        } catch (RuntimeException failure) {
            http.stop(0); workers.shutdown(); rejectedWorkers.shutdown(); throw failure;
        }
    }

    /**
     * 서버를 시작한다. 성공 후 엔진 종료 책임은 서버로 이전되며 close가 모든 자원을 닫는다.
     * @param engine 공유 DB 엔진
     * @param address 바인딩 주소. 포트 0이면 테스트용 자동 할당
     * @param options 고정 워커와 유한 큐 크기
     * @return 실행 중 서버. 시작 실패 시 IOException/IllegalArgumentException
     */
    public static DbHttpServer start(DbEngine engine, InetSocketAddress address, PoolOptions options) throws IOException {
        return new DbHttpServer(engine, address, options);
    }

    /** @return 실제 바인딩된 포트. 상태 변경 없음 */
    public int getPort() { return http.getAddress().getPort(); }

    private static void configureTimeouts() {
        // JDK 21 구현의 연결 제한시간. SQL 실행 시간 측정에는 관여하지 않는다.
        System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "10");
        System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "30");
        System.getProperties().putIfAbsent("sun.net.httpserver.drainAmount", "0");
    }

    private void runRejected(Runnable task) {
        // JDK 내부 작업에서 교환 객체를 만든 뒤, SQL/본문을 처리하기 전에 503만 전송한다.
        isRejected.set(true);
        try { task.run(); }
        finally { isRejected.remove(); }
    }

    private void handle(HttpExchange exchange) {
        try {
            if (isRejected.get()) { sendError(exchange, 503, ErrorCode.SERVER_BUSY); return; }
            dispatch(exchange);
        } catch (RequestFailure failure) { sendErrorSafely(exchange, failure.status, ErrorCode.BAD_REQUEST); }
        catch (IllegalArgumentException failure) { sendErrorSafely(exchange, 400, ErrorCode.BAD_REQUEST); }
        catch (IOException failure) { System.err.println("[handle] 요청 연결의 읽기/쓰기가 중단되었습니다"); }
        catch (RuntimeException failure) { sendErrorSafely(exchange, 500, ErrorCode.INTERNAL_ERROR); }
        catch (Error failure) {
            if (failure instanceof VirtualMachineError) { throw failure; }
            sendErrorSafely(exchange, 500, ErrorCode.INTERNAL_ERROR);
        }
        finally { exchange.close(); }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (path.equals("/health")) {
            if (!exchange.getRequestMethod().equals("GET")) { sendError(exchange, 405, ErrorCode.BAD_REQUEST); }
            else { send(exchange, 200, JsonCodec.toJson(Map.of("status", "ok"))); }
        } else if (path.equals("/sql")) {
            if (!exchange.getRequestMethod().equals("POST")) { sendError(exchange, 405, ErrorCode.BAD_REQUEST); }
            else { executeSql(exchange); }
        } else { sendError(exchange, 404, ErrorCode.BAD_REQUEST); }
    }

    private void executeSql(HttpExchange exchange) throws IOException {
        String sqlText = readSql(exchange);
        DbResult result = engine.execute(sqlText);
        if (!result.isSuccess()) { sendError(exchange, result.getErrorCode().getHttpStatus(), result.getErrorCode()); }
        else {
            send(exchange, 200, JsonCodec.toJson(result.getId() != null
                    ? Map.of("id", result.getId()) : Map.of("rows", result.getRows())));
        }
    }

    private String readSql(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                long sizeBytes = Long.parseLong(length);
                if (sizeBytes < 0) { throw new RequestFailure(400); }
                if (sizeBytes > MAX_BODY_BYTES) { throw new RequestFailure(413); }
            } catch (NumberFormatException failure) { throw new RequestFailure(400); }
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) { throw new RequestFailure(413); }
        Object body;
        try {
            body = JsonCodec.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException failure) { throw new RequestFailure(400); }
        if (!(body instanceof Map<?, ?> fields) || fields.size() != 1
                || !(fields.get("sql") instanceof String sqlText) || sqlText.isBlank()) {
            throw new RequestFailure(400);
        }
        return sqlText;
    }

    private void sendError(HttpExchange exchange, int status, ErrorCode code) throws IOException {
        send(exchange, status, JsonCodec.toJson(Map.of("error", code.name())));
    }

    private void sendErrorSafely(HttpExchange exchange, int status, ErrorCode code) {
        try { sendError(exchange, status, code); }
        catch (IOException failure) { System.err.println("[handle] 오류 응답을 전송할 수 없습니다"); }
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Connection", "close");
        if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(status, -1); return; }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** 수신을 중단하고 작업을 마친 뒤 워커, 엔진 순서로 닫는다. 반복 호출은 안전하다. */
    @Override public void close() {
        if (!isClosed.compareAndSet(false, true)) { return; }
        try { http.stop(SHUTDOWN_GRACE_SECONDS); }
        finally { closeWorkers(); }
    }

    private void closeWorkers() {
        try { workers.shutdown(); }
        finally {
            try { rejectedWorkers.shutdown(); }
            finally {
                try { engine.close(); }
                finally { stopped.countDown(); }
            }
        }
    }

    /** server 옵션으로 실행한다. SIGINT/SIGTERM에 종료 훅이 자원을 닫는다. 성공 0, 실패 1. */
    public static int run(String[] args) {
        try {
            ServerOptions options = new ServerOptions(args);
            try (DbEngine engine = new DbEngine(options.dataDir);
                 DbHttpServer server = start(engine, options.address, options.pool)) {
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "minidb-shutdown"));
                System.out.println("미니 DBMS http://" + options.address.getHostString() + ":" + server.getPort());
                server.stopped.await();
                return 0;
            }
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return 0; }
        catch (IOException | RuntimeException failure) {
            System.err.println("[run] 서버 옵션 또는 데이터 디렉터리를 확인해 주세요");
            return 1;
        }
    }
}
