package minidbms.server;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 서버 실행 옵션. 변경은 생성 시에만 수행하고 실행 중 공유하지 않는다. */
final class ServerOptions {
    private static final Set<String> ALLOWED = Set.of("--host", "--port", "--workers", "--queue", "--data-dir");
    final InetSocketAddress address;
    final Path dataDir;
    final DbHttpServer.PoolOptions pool;

    ServerOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!ALLOWED.contains(args[index]) || index + 1 >= args.length
                    || options.putIfAbsent(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("[run] 서버 옵션을 확인해 주세요");
            }
        }
        int port = positive(options.getOrDefault("--port", "8080"));
        if (port > 65535) { throw new IllegalArgumentException("[run] 포트 범위 오류"); }
        address = new InetSocketAddress(options.getOrDefault("--host", "127.0.0.1"), port);
        dataDir = Path.of(options.getOrDefault("--data-dir", "data"));
        pool = new DbHttpServer.PoolOptions(positive(options.getOrDefault("--workers",
                Integer.toString(Runtime.getRuntime().availableProcessors()))),
                positive(options.getOrDefault("--queue", Integer.toString(DbWorkerPool.DEFAULT_QUEUE_CAPACITY))));
    }

    private int positive(String value) {
        int number = Integer.parseInt(value);
        if (number < 1) { throw new IllegalArgumentException("[run] 옵션 값은 양수여야 합니다"); }
        return number;
    }
}
