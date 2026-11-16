package minidbms.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 직접 만든 스레드 풀. 공용 작업 큐에서 워커 스레드들이 작업을 꺼내 반복 실행한다. (협약 9.3)
 * 요청마다 새 스레드를 만들지 않는다. HttpServer.setExecutor(pool)로 서버에 연결한다.
 *
 * <p>큐 포화 처리 (docs/implementation_notes.md 4장) : 큐가 가득 차면 기다리지 않고 작업을 바로 거절한다.
 * 그런데 JDK HttpServer는 요청 한 건을 "소켓에서 요청을 읽고 핸들러를 부르는 작업 하나"로 만들어
 * 이 풀에 맡기므로, 풀이 예외만 던지면 연결이 그냥 끊겨 클라이언트가 503을 받지 못한다.
 * 그래서 거절된 작업은 풀을 부른 스레드(서버의 접속 수락 스레드)에서 그 자리에서 실행하되
 * {@link #isRejectingCurrentTask()}가 참이 되게 한다. 핸들러는 이 값을 보고 DB 작업 없이 503만 돌려준다.
 * 그 일은 짧아 접속 수락 스레드를 오래 잡지 않는다.
 *
 * <p>워커를 interrupt하지 않는다. 파일 채널은 interrupt되면 닫히기 때문이다.
 * 종료는 "새 작업 거절 → 큐가 빌 때까지 실행 → 워커 종료" 순서다.
 */
public final class DbWorkerPool implements Executor {

    /** 작업 큐 크기 기본값. (협약 9.3) */
    public static final int DEFAULT_QUEUE_CAPACITY = 128;

    private static final long POLL_INTERVAL_MS = 20;

    private final int workerCount;
    private final int queueCapacity;
    private final BlockingQueue<Runnable> taskQueue;
    private final List<Thread> workers = new ArrayList<>();
    private final ReentrantLock acceptLock = new ReentrantLock();
    private final ThreadLocal<Boolean> isRejecting = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicLong rejectedCount = new AtomicLong();
    private volatile boolean isShutdown;

    /**
     * 스레드 풀을 만들고 워커를 띄운다.
     *
     * @param workerCount   워커 스레드 수 (1 이상). 기본값은 CPU 수다
     * @param queueCapacity 작업 큐 크기 (1 이상)
     * @throws IllegalArgumentException 수가 1보다 작은 경우
     */
    public DbWorkerPool(int workerCount, int queueCapacity) {
        if (workerCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("[DbWorkerPool] 워커 수와 큐 크기는 1 이상이어야 합니다");
        }
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.taskQueue = new ArrayBlockingQueue<>(queueCapacity);
        for (int i = 1; i <= workerCount; i++) {
            Thread worker = new Thread(this::runWorkerLoop, "db-worker-" + i);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * 작업을 큐에 넣는다. 큐가 가득 차면 기다리지 않고 부른 스레드에서 거절 모드로 바로 실행한다.
     * 거절 모드에서는 {@link #isRejectingCurrentTask()}가 참이다.
     *
     * @param task 실행할 작업
     * @throws RejectedExecutionException 이미 shutdown한 풀인 경우
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        boolean isAccepted;
        acceptLock.lock();
        try {
            if (isShutdown) {
                throw new RejectedExecutionException("[execute] 종료 중인 풀입니다");
            }
            isAccepted = taskQueue.offer(task);
        } finally {
            acceptLock.unlock();
        }
        if (!isAccepted) {
            rejectedCount.incrementAndGet();
            runAsRejected(task);
        }
    }

    /**
     * 새 작업을 받지 않고, 큐에 남은 작업과 실행 중인 작업을 마친 뒤 워커를 멈춘다.
     * 기다리지 않고 돌아온다. 끝나기를 기다리려면 {@link #awaitTermination}을 부른다.
     */
    public void shutdown() {
        acceptLock.lock();
        try {
            isShutdown = true;
        } finally {
            acceptLock.unlock();
        }
    }

    /**
     * 모든 워커가 끝날 때까지 기다린다.
     *
     * @param timeout 최대 대기 시간
     * @param unit    시간 단위
     * @return 시간 안에 모두 끝났으면 true
     * @throws InterruptedException 기다리다 interrupt된 경우
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        for (Thread worker : workers) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                return !worker.isAlive();
            }
            worker.join(remainingMs);
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 지금 이 스레드가 큐 포화로 거절된 작업을 실행 중인지 알려 준다. 핸들러가 503을 돌려줄지 정한다.
     *
     * @return 거절된 작업을 실행 중이면 true
     */
    public boolean isRejectingCurrentTask() {
        return isRejecting.get();
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

    /**
     * @return 지금 큐에서 기다리는 작업 수
     */
    public int getQueuedCount() {
        return taskQueue.size();
    }

    /**
     * @return 큐가 가득 차 거절한 작업의 누적 수
     */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    private void runWorkerLoop() {
        while (true) {
            Runnable task = pollTask();
            if (task != null) {
                runTask(task);
            } else if (isShutdown && taskQueue.isEmpty()) {
                return;
            }
        }
    }

    private Runnable pollTask() {
        try {
            return taskQueue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 작업 하나의 오류가 워커 스레드를 죽이지 않게 요청 단위로 막는다. (협약 9.3) */
    private void runTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable error) {
            System.err.println("[runTask] 작업 실행 중 오류, 워커는 계속 동작합니다: " + error);
        }
    }

    private void runAsRejected(Runnable task) {
        isRejecting.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            isRejecting.remove();
        }
    }
}
