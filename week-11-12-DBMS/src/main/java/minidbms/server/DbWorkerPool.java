package minidbms.server;

import java.util.concurrent.Executor;

/**
 * 직접 만든 스레드 풀. 공용 작업 큐에서 워커 스레드들이 작업을 꺼내 반복 실행한다. (협약 9.3)
 * 요청마다 새 스레드를 만들지 않는다. HttpServer.setExecutor(pool)로 서버에 연결한다.
 */
public final class DbWorkerPool implements Executor {

    /** 작업 큐 크기 기본값. (협약 9.3) */
    public static final int DEFAULT_QUEUE_CAPACITY = 128;

    private final int workerCount;
    private final int queueCapacity;

    /**
     * 스레드 풀을 만들고 워커를 띄운다.
     *
     * @param workerCount   워커 스레드 수. 기본값은 CPU 수다
     * @param queueCapacity 작업 큐 크기
     */
    public DbWorkerPool(int workerCount, int queueCapacity) {
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        // TODO(이니셜): BlockingQueue<Runnable>과 워커 스레드 만들기 (협약 9.3)
    }

    /**
     * 작업을 큐에 넣는다. 큐가 가득 차면 기다리지 않고 거절을 알린다. (503)
     * 거절 방법은 각자 설계하고 docs/implementation_notes.md에 쓴다.
     *
     * @param task 실행할 작업
     */
    @Override
    public void execute(Runnable task) {
        // TODO(이니셜): 협약 9.3
        throw new UnsupportedOperationException("[execute] 아직 구현되지 않았습니다");
    }

    /**
     * 새 작업을 받지 않고, 남은 작업을 마친 뒤 워커를 멈춘다.
     */
    public void shutdown() {
        // TODO(이니셜): 협약 9.3
        throw new UnsupportedOperationException("[shutdown] 아직 구현되지 않았습니다");
    }

    /**
     * @return 워커 스레드 수
     */
    public int getWorkerCount() {
        return workerCount;
    }

    /**
     * @return 작업 큐 크기
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }
}
