package minidbms.server;

/**
 * HTTP API 서버. JDK HttpServer로 POST /sql, GET /health를 받는다. (협약 9.3, 10.3)
 * 요청·응답을 변환하고 DbEngine.execute만 부른다. storage·index를 직접 부르지 않는다. (협약 6장)
 */
public final class DbHttpServer {

    private DbHttpServer() {
    }

    /**
     * server 명령을 실행한다.
     *
     * @param args "server" 뒤의 인자. --host --port --workers --queue --data-dir
     * @return 정상 종료하면 0, 시작에 실패하면 1
     */
    public static int run(String[] args) {
        // TODO(이니셜): 옵션 읽기 → 엔진 열기 → DbWorkerPool 연결 → 서버 시작 (협약 9.3, 10.3)
        throw new UnsupportedOperationException("[run] server 명령은 아직 구현되지 않았습니다");
    }
}
