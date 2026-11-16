package minidbms.server;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 고정 워커와 유한 큐로 구성한 직접 구현 스레드 풀.
 * 승인한 작업은 종료 시 모두 마치며, 큐 포화와 종료 이후 제출은 즉시 거절한다.
 */
public final class DbWorkerPool implements Executor {
    public static final int DEFAULT_QUEUE_CAPACITY = 128;
    private static final long SHUTDOWN_POLL_MS = 100;

    private final int workerCount;
    private final int queueCapacity;
    private final BlockingQueue<Runnable> tasks;
    private final Thread[] workers;
    private final Consumer<Runnable> rejectionHandler;
    private final Object lifecycle = new Object();
    private volatile boolean isAccepting = true;

    /** 양수 크기의 풀을 생성하고 워커를 시작한다. 작업 스레드는 재사용한다. */
    public DbWorkerPool(int workerCount, int queueCapacity) {
        this(workerCount, queueCapacity, task -> {
            throw new RejectedExecutionException("[execute] 작업 큐가 가득 찼거나 종료 중입니다");
        });
    }

    DbWorkerPool(int workerCount, int queueCapacity, Consumer<Runnable> rejectionHandler) {
        if (workerCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("[DbWorkerPool] 워커와 큐 크기는 양수여야 합니다");
        }
        this.rejectionHandler = Objects.requireNonNull(rejectionHandler);
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        tasks = new ArrayBlockingQueue<>(queueCapacity);
        workers = new Thread[workerCount];
        for (int index = 0; index < workerCount; index++) {
            workers[index] = new Thread(this::work, "minidb-worker-" + index);
            workers[index].start();
        }
    }

    /**
     * 큐에 작업을 추가한다. 기다리지 않는다.
     * @throws RejectedExecutionException 포화 상태 또는 종료 중일 때
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        boolean isAccepted;
        synchronized (lifecycle) { isAccepted = isAccepting && tasks.offer(task); }
        if (!isAccepted) { rejectionHandler.accept(task); }
    }

    private void work() {
        while (isAccepting || !tasks.isEmpty()) {
            try {
                Runnable task = tasks.poll(SHUTDOWN_POLL_MS, TimeUnit.MILLISECONDS);
                if (task != null) { runTask(task); }
            } catch (InterruptedException failure) {
                // 승인된 작업이 남으면 인터럽트를 복원해 poll이 반복 실패하지 않게 한다.
                if (!isAccepting && tasks.isEmpty()) { return; }
            }
        }
    }

    private void runTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable failure) {
            System.err.println("[DbWorkerPool] 작업 실패: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * 새 작업 제출을 중단하고 승인된 작업 완료 후 모든 워커를 join한다.
     * 여러 번 호출해도 된다. 워커 내부에서의 호출은 자기 join을 피하려고 거부한다.
     */
    public void shutdown() {
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) {
                throw new IllegalStateException("[shutdown] 워커 안에서 종료를 기다릴 수 없습니다");
            }
        }
        synchronized (lifecycle) { isAccepting = false; }
        boolean isInterrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException failure) { isInterrupted = true; }
            }
        }
        if (isInterrupted) { Thread.currentThread().interrupt(); }
    }

    /** @return 고정 워커 수. 상태 변경 없음 */
    public int getWorkerCount() {
        return workerCount;
    }

    /** @return 작업 큐 최대 크기. 상태 변경 없음 */
    public int getQueueCapacity() {
        return queueCapacity;
    }
}
