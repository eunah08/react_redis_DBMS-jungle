package minidbms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 협약 9.3 : 고정 워커 재사용, 큐 한도, 거절 처리, 종료 순서 검증.
 */
class DbWorkerPoolTest {

    private static final long WAIT_SECONDS = 10;

    @Test
    void 워커_수만큼의_스레드가_작업을_재사용해_처리한다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(3, 100);
        Set<String> threadNames = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch done = new CountDownLatch(60);

        for (int i = 0; i < 60; i++) {
            pool.execute(() -> {
                threadNames.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(threadNames.size() <= 3, "스레드가 " + threadNames.size() + "개 쓰였습니다");
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS * 1000));
    }

    @Test
    void 큐가_차면_거절_처리기가_불린다() throws Exception {
        List<Runnable> rejected = Collections.synchronizedList(new ArrayList<>());
        DbWorkerPool pool = new DbWorkerPool(1, 1, rejected::add);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        pool.execute(() -> {
            running.countDown();
            await(release);
        });
        assertTrue(running.await(WAIT_SECONDS, TimeUnit.SECONDS));
        pool.execute(() -> { });

        Runnable third = () -> { };
        pool.execute(third);

        assertEquals(List.of(third), rejected);
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS * 1000));
    }

    @Test
    void 기본_거절은_RejectedExecutionException이다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        pool.execute(() -> {
            running.countDown();
            await(release);
        });
        assertTrue(running.await(WAIT_SECONDS, TimeUnit.SECONDS));
        pool.execute(() -> { });

        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));

        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS * 1000));
    }

    @Test
    void 작업이_예외를_던져도_워커는_계속_일한다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 10);
        CountDownLatch done = new CountDownLatch(1);

        pool.execute(() -> {
            throw new IllegalStateException("일부러 낸 오류");
        });
        pool.execute(done::countDown);

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(WAIT_SECONDS * 1000));
    }

    @Test
    void 종료하면_큐에_남은_작업까지_끝내고_새_작업은_받지_않는다() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 50);
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            await(release);
            finished.incrementAndGet();
        });
        for (int i = 0; i < 20; i++) {
            pool.execute(finished::incrementAndGet);
        }

        pool.shutdown();
        release.countDown();

        assertTrue(pool.awaitTermination(WAIT_SECONDS * 1000));
        assertEquals(21, finished.get());
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
    }

    @Test
    void 잘못된_인자는_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new DbWorkerPool(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DbWorkerPool(1, 0));
        assertFalse(new DbWorkerPool(2, 3).getWorkerCount() != 2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
