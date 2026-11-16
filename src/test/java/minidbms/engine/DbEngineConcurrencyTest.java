package minidbms.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 협약 9.3, 11.3 : 동시 INSERT/SELECT에서 id 중복, 반쪽 행, 읽기 동시 진행이 없는지 검증.
 */
class DbEngineConcurrencyTest {

    private static final int TIMEOUT_SECONDS = 30;

    private static void writeSchema(Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("products.schema"), "name string 16\nprice int\n");
    }

    private static <T> List<T> runAll(List<Callable<T>> jobs) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(jobs.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> job : jobs) {
                futures.add(threads.submit(job));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void 동시_INSERT도_id가_겹치지_않고_모두_저장된다(@TempDir Path dataDir) throws Exception {
        writeSchema(dataDir);
        DbEngine engine = new DbEngine(dataDir);
        int threadCount = 8;
        int perThread = 200;
        List<Callable<List<Integer>>> jobs = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            jobs.add(() -> {
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    DbResult result = engine.execute("INSERT INTO products (name, price) VALUES ('x', 1);");
                    assertFalse(result.isError());
                    ids.add(result.getInsertedId());
                }
                return ids;
            });
        }

        Set<Integer> allIds = new HashSet<>();
        for (List<Integer> ids : runAll(jobs)) {
            allIds.addAll(ids);
        }

        assertEquals(threadCount * perThread, allIds.size());
        assertEquals(threadCount * perThread, engine.execute("SELECT * FROM products;").getRows().size());
        for (int id = 1; id <= threadCount * perThread; id++) {
            assertTrue(allIds.contains(id), "id " + id + "가 빠졌습니다");
        }
        engine.close();
    }

    @Test
    void 읽기_두_개는_서로_기다리지_않고_함께_진행한다(@TempDir Path dataDir) throws Exception {
        writeSchema(dataDir);
        CyclicBarrier bothInside = new CyclicBarrier(2);
        DbEngine engine = new DbEngine(dataDir, () -> {
            try {
                bothInside.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("두 읽기가 동시에 읽기 락 안에 있지 못했습니다: " + e);
            }
        });
        engine.execute("INSERT INTO products (name, price) VALUES ('a', 1);");
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            jobs.add(() -> !engine.execute("SELECT * FROM products;").isError());
        }

        List<Boolean> results = runAll(jobs);

        assertEquals(List.of(true, true), results);
        engine.close();
    }

    @Test
    void INSERT와_SELECT가_섞여도_반쪽_행을_보지_않는다(@TempDir Path dataDir) throws Exception {
        writeSchema(dataDir);
        DbEngine engine = new DbEngine(dataDir);
        // price가 항상 id와 같도록 쓰려면 id를 알아야 하므로, 이름에 번호를 넣고 행 모양만 검사한다
        int writes = 400;
        List<Callable<Boolean>> jobs = new ArrayList<>();
        jobs.add(() -> {
            for (int i = 1; i <= writes; i++) {
                DbResult result = engine.execute("INSERT INTO products (name, price) VALUES ('n" + i + "', " + i + ");");
                assertFalse(result.isError());
            }
            return true;
        });
        for (int reader = 0; reader < 3; reader++) {
            jobs.add(() -> {
                for (int i = 0; i < 100; i++) {
                    DbResult result = engine.execute("SELECT * FROM products;");
                    assertFalse(result.isError());
                    for (List<Object> row : result.getRows()) {
                        assertEquals(3, row.size());
                        assertEquals("n" + row.get(2), row.get(1));
                    }
                }
                return true;
            });
        }

        runAll(jobs);

        assertEquals(writes, engine.execute("SELECT * FROM products;").getRows().size());
        engine.close();
    }

    @Test
    void 서로_다른_테이블은_동시에_쓸_수_있다(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("a.schema"), "v int\n");
        Files.writeString(dataDir.resolve("b.schema"), "v int\n");
        DbEngine engine = new DbEngine(dataDir);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (String table : List.of("a", "b")) {
            jobs.add(() -> {
                int last = 0;
                for (int i = 0; i < 300; i++) {
                    last = engine.execute("INSERT INTO " + table + " (v) VALUES (" + i + ");").getInsertedId();
                }
                return last;
            });
        }

        assertEquals(List.of(300, 300), runAll(jobs));
        engine.close();
    }
}
