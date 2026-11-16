package minidbms.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DbWorkerPoolTest {
    @Test
    void boundedQueueRejectsAndShutdownDrainsAcceptedTasks() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        pool.execute(() -> { started.countDown(); await(release); completed.incrementAndGet(); });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.execute(completed::incrementAndGet);
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
        } finally {
            release.countDown(); pool.shutdown();
        }
        assertEquals(2, completed.get());
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
        pool.shutdown();
    }

    @Test
    void reusesWorkersRunsInParallelAndSurvivesTaskException() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(2, 20);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        Runnable blocked = () -> { threads.add(Thread.currentThread()); started.countDown(); await(release); };
        pool.execute(blocked); pool.execute(blocked);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.execute(() -> { throw new IllegalStateException("expected test failure"); });
            for (int index = 0; index < 10; index++) {
                pool.execute(() -> threads.add(Thread.currentThread()));
            }
        } finally { release.countDown(); pool.shutdown(); }
        assertEquals(2, threads.size());
        assertTrue(threads.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void assertionErrorDoesNotLoseAlreadyAcceptedWork() throws Exception {
        DbWorkerPool pool = new DbWorkerPool(1, 4);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        pool.execute(() -> { failed.countDown(); throw new AssertionError("injected task error"); });
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        pool.execute(completed::countDown);
        pool.shutdown();
        assertEquals(0, completed.getCount());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
