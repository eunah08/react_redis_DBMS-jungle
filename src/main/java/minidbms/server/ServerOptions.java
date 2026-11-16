package minidbms.server;

import java.nio.file.Path;

/**
 * server 명령의 옵션. (협약 10.3)
 *
 * @param host    바인딩할 주소
 * @param port    포트. 0이면 빈 포트를 고른다
 * @param workers 워커 스레드 수
 * @param queue   작업 큐 크기
 * @param dataDir 데이터 디렉터리
 */
public record ServerOptions(String host, int port, int workers, int queue, Path dataDir) {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_DATA_DIR = "data";
    private static final int MAX_PORT = 65535;

    /**
     * 기본값으로 옵션을 만든다. 워커 수는 CPU 수, 큐 크기는 128이다. (협약 9.3)
     *
     * @return 기본 옵션
     */
    public static ServerOptions defaults() {
        return new ServerOptions(DEFAULT_HOST, DEFAULT_PORT, Runtime.getRuntime().availableProcessors(),
                DbWorkerPool.DEFAULT_QUEUE_CAPACITY, Path.of(DEFAULT_DATA_DIR));
    }

    /**
     * 실행 인자를 읽는다. --host --port --workers --queue --data-dir
     *
     * @param args "server" 뒤의 인자
     * @return 옵션
     * @throws IllegalArgumentException 모르는 옵션이거나 값이 올바르지 않을 때
     */
    public static ServerOptions parse(String[] args) {
        ServerOptions options = defaults();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(args[i] + " 뒤에 값이 필요합니다");
            }
            options = options.withOption(args[i], args[i + 1]);
        }
        return options.validated();
    }

    private ServerOptions withOption(String name, String value) {
        return switch (name) {
            case "--host" -> new ServerOptions(value, port, workers, queue, dataDir);
            case "--port" -> new ServerOptions(host, toInt(name, value), workers, queue, dataDir);
            case "--workers" -> new ServerOptions(host, port, toInt(name, value), queue, dataDir);
            case "--queue" -> new ServerOptions(host, port, workers, toInt(name, value), dataDir);
            case "--data-dir" -> new ServerOptions(host, port, workers, queue, Path.of(value));
            default -> throw new IllegalArgumentException("알 수 없는 옵션입니다: " + name);
        };
    }

    private ServerOptions validated() {
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("--port는 0~" + MAX_PORT + "이어야 합니다");
        }
        if (workers < 1 || queue < 1) {
            throw new IllegalArgumentException("--workers와 --queue는 1 이상이어야 합니다");
        }
        return this;
    }

    private static int toInt(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 값이 숫자가 아닙니다: " + value);
        }
    }
}
