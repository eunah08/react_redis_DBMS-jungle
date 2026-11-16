package minidbms.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import minidbms.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 9.3 "지켜야 할 것" 표, 11.2 Phase 3, 13장 서버 항목을 엔진 수준에서 확인한다.
 * 시간 제한이 있는 동기화 지점을 써서 실행 순서에 기대지 않는다.
 */
class DbEngineConcurrencyTest {

    private static final long WAIT_SECONDS = 20;

    @TempDir
    Path tempDir;

    private DbEngine engine;

    @BeforeEach
    void setUp() {
        Path dataDir = TestSupport.prepareDataDir(tempDir);
        TestSupport.writeSchema(dataDir, "other", "title string 20\n");
        engine = new DbEngine(dataDir);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void 동시_INSERT의_id는_겹치지_않고_빈_번호가_없고_모든_행이_조회된다() throws Exception {
        int threadCount = 8;
        int perThread = 250;
        ExecutorService threads = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier start = new CyclicBarrier(threadCount);
        try {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                int threadNumber = t;
                futures.add(threads.submit(() -> insertMany(start, threadNumber, perThread)));
            }
            Set<Integer> ids = new TreeSet<>();
            for (Future<List<Integer>> future : futures) {
                for (int id : future.get(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    assertTrue(ids.add(id), "id가 겹쳤습니다: " + id);
                }
            }

            int total = threadCount * perThread;
            assertEquals(total, ids.size());
            assertEquals(1, ((TreeSet<Integer>) ids).first());
            assertEquals(total, ((TreeSet<Integer>) ids).last());
            assertEquals(total, engine.getIndexSize("products"));
            assertRowsComplete(total);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void INSERT가_진행되는_동안_SELECT는_완성된_행만_본다() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(6);
        AtomicBoolean isWriting = new AtomicBoolean(true);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(threads.submit(() -> readUntilStopped(isWriting)));
            }
            List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < 3; w++) {
                int writerNumber = w;
                writers.add(threads.submit(() -> insertMany(new CyclicBarrier(1), writerNumber, 300)));
            }
            for (Future<?> writer : writers) {
                writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            isWriting.set(false);
            for (Future<?> reader : readers) {
                reader.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }

            assertRowsComplete(900);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void 두_SELECT는_같은_테이블의_읽기_구간에_함께_들어간다() throws Exception {
        TestSupport.requireSuccess(engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);"));
        CyclicBarrier bothInside = new CyclicBarrier(2);
        engine.setSelectHook(() -> awaitBarrier(bothInside));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<DbResult> first = threads.submit(() -> engine.execute("SELECT * FROM products;"));
            Future<DbResult> second = threads.submit(() -> engine.execute("SELECT * FROM products WHERE id = 1;"));

            assertFalse(first.get(WAIT_SECONDS, TimeUnit.SECONDS).isError());
            assertFalse(second.get(WAIT_SECONDS, TimeUnit.SECONDS).isError());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void 읽기_락을_잡은_동안_같은_테이블_INSERT는_기다리고_다른_테이블은_막히지_않는다() throws Exception {
        TestSupport.requireSuccess(engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);"));
        CountDownLatch readerInside = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        engine.setSelectHook(() -> {
            if (Thread.currentThread().getName().equals("holder")) {
                readerInside.countDown();
                awaitLatch(releaseReader);
            }
        });
        Thread holder = new Thread(() -> engine.execute("SELECT * FROM products;"), "holder");
        holder.start();
        assertTrue(readerInside.await(WAIT_SECONDS, TimeUnit.SECONDS));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<DbResult> sameTable = threads.submit(
                    () -> engine.execute("INSERT INTO products (name, price) VALUES ('b', 2);"));
            Future<DbResult> otherTable = threads.submit(
                    () -> engine.execute("INSERT INTO other (title) VALUES ('x');"));

            assertFalse(otherTable.get(WAIT_SECONDS, TimeUnit.SECONDS).isError(), "다른 테이블은 막히면 안 된다");
            Thread.sleep(200);
            assertFalse(sameTable.isDone(), "같은 테이블 INSERT는 읽는 동안 기다려야 한다");
            releaseReader.countDown();
            assertFalse(sameTable.get(WAIT_SECONDS, TimeUnit.SECONDS).isError());
        } finally {
            releaseReader.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            threads.shutdownNow();
        }
    }

    @Test
    void 서로_다른_테이블에_동시_INSERT해도_각자의_id가_1부터_이어진다() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            List<Future<DbResult>> productFutures = new ArrayList<>();
            List<Future<DbResult>> otherFutures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                productFutures.add(threads.submit(
                        () -> engine.execute("INSERT INTO products (name, price) VALUES ('p', 1);")));
                otherFutures.add(threads.submit(() -> engine.execute("INSERT INTO other (title) VALUES ('t');")));
            }

            assertEquals(idsFrom(productFutures), idsFrom(otherFutures));
            assertEquals(100, idsFrom(productFutures).size());
            assertEquals(100, idsFrom(otherFutures).last());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void 선형_탐색이_도는_동안에도_다른_테이블_요청은_처리된다() throws Exception {
        for (int i = 1; i <= 2000; i++) {
            TestSupport.requireSuccess(engine.execute(
                    "INSERT INTO products (name, price) VALUES ('item" + i + "', " + i + ");"));
        }
        CountDownLatch scanInside = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        engine.setSelectHook(() -> {
            if (Thread.currentThread().getName().equals("scanner")) {
                scanInside.countDown();
                awaitLatch(releaseScan);
            }
        });
        Thread scanner = new Thread(() -> engine.execute("SELECT * FROM products WHERE name = 'item2000';"), "scanner");
        scanner.start();
        assertTrue(scanInside.await(WAIT_SECONDS, TimeUnit.SECONDS));
        try {
            DbResult other = engine.execute("INSERT INTO other (title) VALUES ('x');");
            DbResult read = engine.execute("SELECT * FROM products WHERE id = 5;");

            assertFalse(other.isError());
            assertFalse(read.isError(), "읽기끼리는 서로 막지 않는다");
        } finally {
            releaseScan.countDown();
            scanner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
    }

    private List<Integer> insertMany(CyclicBarrier start, int threadNumber, int count) throws Exception {
        start.await(WAIT_SECONDS, TimeUnit.SECONDS);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int serial = threadNumber * 1000 + i;
            DbResult result = engine.execute(
                    "INSERT INTO products (name, price) VALUES ('n" + serial + "', " + serial + ");");
            ids.add(TestSupport.requireSuccess(result).getInsertedId());
        }
        return ids;
    }

    /** 모든 행이 name = 'n' + price 로 완성되어 있는지 본다. 반쯤 쓴 행이 보이면 깨진다. */
    private void readUntilStopped(AtomicBoolean isWriting) {
        do {
            DbResult result = TestSupport.requireSuccess(engine.execute("SELECT * FROM products;"));
            for (List<Object> row : result.getRows()) {
                assertEquals("n" + row.get(2), row.get(1), "반쯤 쓴 행을 읽었습니다: " + row);
            }
        } while (isWriting.get());
    }

    private void assertRowsComplete(int expectedCount) {
        DbResult all = TestSupport.requireSuccess(engine.execute("SELECT * FROM products;"));
        assertEquals(expectedCount, all.getRows().size());
        for (int i = 0; i < expectedCount; i++) {
            List<Object> row = all.getRows().get(i);
            assertEquals(i + 1, row.get(0), "id 오름차순이고 빈 번호가 없어야 한다");
            assertEquals("n" + row.get(2), row.get(1));
            DbResult byIndex = engine.execute("SELECT * FROM products WHERE id = " + (i + 1) + ";");
            assertEquals(List.of(row), byIndex.getRows());
        }
    }

    private static TreeSet<Integer> idsFrom(List<Future<DbResult>> futures) throws Exception {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Future<DbResult> future : futures) {
            ids.add(TestSupport.requireSuccess(future.get(WAIT_SECONDS, TimeUnit.SECONDS)).getInsertedId());
        }
        return ids;
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 SELECT가 읽기 구간에 함께 들어가지 못했습니다", e);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
