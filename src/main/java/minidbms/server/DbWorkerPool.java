package minidbms.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 직접 만든 스레드 풀. 공용 작업 큐에서 워커 스레드들이 작업을 꺼내 반복 실행한다. (협약 9.3)
 * 요청마다 새 스레드를 만들지 않는다. HttpServer.setExecutor(pool)로 서버에 연결한다.
 * 큐는 크기가 정해진 ArrayBlockingQueue다. 가득 차면 기다리지 않고 거절 처리기를 부른다.
 * 거절 처리기를 주지 않으면 RejectedExecutionException을 던진다.
 */
public final class DbWorkerPool implements Executor {

    /** 작업 큐 크기 기본값. (협약 9.3) */
    public static final int DEFAULT_QUEUE_CAPACITY = 128;

    private static final long POLL_MILLIS = 50;

    private final int workerCount;
    private final int queueCapacity;
    private final BlockingQueue<Runnable> taskQueue;
    private final Consumer<Runnable> rejectHandler;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean isShutdown;

    /**
     * 스레드 풀을 만들고 워커를 띄운다. 큐가 가득 차면 RejectedExecutionException을 던진다.
     *
     * @param workerCount   워커 스레드 수. 기본값은 CPU 수다
     * @param queueCapacity 작업 큐 크기
     */
    public DbWorkerPool(int workerCount, int queueCapacity) {
        this(workerCount, queueCapacity, task -> {
            throw new RejectedExecutionException("[execute] 작업 큐가 가득 찼거나 풀이 종료되었습니다");
        });
    }

    /**
     * 스레드 풀을 만들고 워커를 띄운다.
     *
     * @param workerCount   워커 스레드 수
     * @param queueCapacity 작업 큐 크기
     * @param rejectHandler 큐가 가득 찼거나 종료된 뒤 들어온 작업을 처리하는 곳. 호출한 스레드에서 바로 실행된다
     */
    public DbWorkerPool(int workerCount, int queueCapacity, Consumer<Runnable> rejectHandler) {
        if (workerCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("[DbWorkerPool] 워커 수와 큐 크기는 1 이상이어야 합니다");
        }
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.taskQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.rejectHandler = rejectHandler;
        startWorkers();
    }

    /**
     * 작업을 큐에 넣는다. 큐가 가득 차면 기다리지 않고 거절을 알린다. (503)
     * 거절 방법은 거절 처리기가 정한다. docs/implementation_notes.md에 쓴다.
     *
     * @param task 실행할 작업
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "[execute] 작업이 null입니다");
        boolean isAccepted;
        synchronized (this) {
            isAccepted = !isShutdown && taskQueue.offer(task);
        }
        if (!isAccepted) {
            rejectHandler.accept(task);
        }
    }

    /**
     * 새 작업을 받지 않고, 남은 작업을 마친 뒤 워커를 멈춘다. 기다리지 않고 바로 돌아온다.
     * 끝날 때까지 기다리려면 awaitTermination을 부른다.
     */
    public void shutdown() {
        synchronized (this) {
            isShutdown = true;
        }
    }

    /**
     * 모든 워커가 끝날 때까지 기다린다.
     *
     * @param timeoutMillis 최대 기다릴 시간
     * @return 시간 안에 모두 끝났으면 true
     * @throws InterruptedException 기다리는 중 인터럽트되었을 때
     */
    public boolean awaitTermination(long timeoutMillis) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread worker : workers) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis > 0) {
                worker.join(remainingMillis);
            }
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
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

    private void startWorkers() {
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(this::runWorker, "db-worker-" + (i + 1));
            workers.add(worker);
            worker.start();
        }
    }

    /** 작업이 있으면 꺼내 실행하고, 종료 요청 뒤 큐가 비면 끝낸다. */
    private void runWorker() {
        while (true) {
            Runnable task = pollTask();
            if (task != null) {
                runSafely(task);
            } else if (isShutdown && taskQueue.isEmpty()) {
                return;
            }
        }
    }

    private Runnable pollTask() {
        if (Thread.currentThread().isInterrupted()) {
            return taskQueue.poll();
        }
        try {
            return taskQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            shutdown();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 작업 하나가 실패해도 워커가 죽지 않게 한다. */
    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable error) {
            System.err.println("[runSafely] 작업 중 오류가 발생했지만 워커는 계속 동작합니다: " + error);
        }
    }
}
