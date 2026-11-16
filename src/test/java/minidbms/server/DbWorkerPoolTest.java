package minidbms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 협약 9.3, 11.1 : 워커 풀 단위 테스트. 시간 제한이 있는 동기화 지점으로 결정적으로 확인한다.
 */
class DbWorkerPoolTest {

    private static final long WAIT_SECONDS = 10;

    @Test
    void 작업은_정해진_수의_워커_스레드에서만_실행된다() throws InterruptedException {
        DbWorkerPool pool = new DbWorkerPool(3, 128);
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            pool.execute(() -> {
                threadNames.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(threadNames.size() <= 3, threadNames.toString());
        assertTrue(threadNames.stream().allMatch(name -> name.startsWith("db-worker-")));
        finish(pool);
    }

    @Test
    void 워커_수만큼_작업이_동시에_실행된다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(4, 16);
        CyclicBarrier barrier = new CyclicBarrier(4);
        AtomicInteger passed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            pool.execute(() -> {
                try {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    passed.incrementAndGet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(4, passed.get(), "4개가 서로를 기다리며 함께 실행되어야 한다");
        finish(pool);
    }

    @Test
    void 큐가_가득_차면_기다리지_않고_거절_모드로_바로_실행한다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(release);
        });
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        pool.execute(() -> { });
        assertEquals(1, pool.getQueuedCount());
        AtomicBoolean wasRejecting = new AtomicBoolean();
        AtomicBoolean ranOnCaller = new AtomicBoolean();
        Thread caller = Thread.currentThread();

        pool.execute(() -> {
            wasRejecting.set(pool.isRejectingCurrentTask());
            ranOnCaller.set(Thread.currentThread() == caller);
        });

        assertTrue(wasRejecting.get());
        assertTrue(ranOnCaller.get());
        assertFalse(pool.isRejectingCurrentTask(), "거절 모드는 그 작업 동안만 켜진다");
        assertEquals(1, pool.getRejectedCount());
        release.countDown();
        finish(pool);
    }

    @Test
    void 작업에서_오류가_나도_워커는_죽지_않는다() throws InterruptedException {
        DbWorkerPool pool = new DbWorkerPool(1, 8);
        CountDownLatch done = new CountDownLatch(1);

        pool.execute(() -> {
            throw new IllegalStateException("일부러 낸 오류");
        });
        pool.execute(() -> {
            throw new AssertionError("Error도 막아야 한다");
        });
        pool.execute(done::countDown);

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "오류 뒤의 작업이 같은 워커에서 실행되어야 한다");
        finish(pool);
    }

    @Test
    void shutdown은_남은_작업을_마친_뒤_워커를_멈추고_새_작업을_거절한다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 16);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        pool.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(release);
            completed.incrementAndGet();
        });
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            pool.execute(completed::incrementAndGet);
        }

        pool.shutdown();

        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
        assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS), "실행 중인 작업이 있는 동안은 끝나지 않는다");
        release.countDown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(6, completed.get(), "큐에 있던 작업까지 모두 실행해야 한다");
    }

    @Test
    void 잘못된_인자는_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new DbWorkerPool(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DbWorkerPool(1, 0));
        assertThrows(NullPointerException.class, () -> {
            DbWorkerPool pool = new DbWorkerPool(1, 1);
            try {
                pool.execute(null);
            } finally {
                pool.shutdown();
            }
        });
    }

    private static void finish(DbWorkerPool pool) throws InterruptedException {
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
