package minidbms.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidbms.server.DbWorkerPool;
import minidbms.storage.ChannelDecorator;

class DbEngineConcurrencyTest {
    @TempDir Path dataDir;
    private static final String INSERT = "INSERT INTO products (name, price) VALUES ('whole', 7);";

    @BeforeEach void schemas() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
        Files.writeString(dataDir.resolve("users.schema"), "label STRING 32\n");
    }

    @Test
    void twoWorkersEnterSameTablesReadSectionTogether() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            engine.execute(INSERT);
            CountDownLatch readers = new CountDownLatch(2);
            EngineTestAccess.decorateChannel(engine, "products", channel -> new ChannelDecorator(channel) {
                @Override public int read(ByteBuffer target, long position) throws IOException {
                    readers.countDown();
                    try {
                        if (!readers.await(2, TimeUnit.SECONDS)) { throw new IOException("readers serialized"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new IOException(failure);
                    }
                    return delegate.read(target, position);
                }
            });
            DbWorkerPool pool = new DbWorkerPool(2, 4);
            try {
                var first = submit(pool, () -> engine.execute("SELECT * FROM products WHERE id = 1;"));
                var second = submit(pool, () -> engine.execute("SELECT * FROM products WHERE id = 1;"));
                assertTrue(first.get(4, TimeUnit.SECONDS).isSuccess());
                assertTrue(second.get(4, TimeUnit.SECONDS).isSuccess());
                assertEquals(0, readers.getCount());
            } finally { pool.shutdown(); }
        }
    }

    @Test
    void concurrentInsertsAndReadsHaveUniqueContinuousIdsAndWholeRows() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            DbWorkerPool pool = new DbWorkerPool(8, 512);
            List<CompletableFuture<DbResult>> inserts = new ArrayList<>();
            List<CompletableFuture<DbResult>> selects = new ArrayList<>();
            try {
                for (int index = 0; index < 200; index++) {
                    inserts.add(submit(pool, () -> engine.execute(INSERT)));
                    selects.add(submit(pool, () -> engine.execute("SELECT * FROM products;")));
                }
                var ids = new HashSet<Integer>();
                for (var future : inserts) { ids.add(future.get(10, TimeUnit.SECONDS).getId()); }
                assertEquals(new HashSet<>(java.util.stream.IntStream.rangeClosed(1, 200).boxed().toList()), ids);
                for (var future : selects) { assertWholeRows(future.get(10, TimeUnit.SECONDS)); }
                assertEquals(200, engine.execute("SELECT * FROM products;").getRows().size());
            } finally { pool.shutdown(); }
        }
    }

    @Test
    void blockedWriterDoesNotBlockAnotherTable() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            Lock firstLock = state(engine, "products").lock.writeLock();
            firstLock.lock();
            DbWorkerPool pool = new DbWorkerPool(2, 4);
            try {
                var blocked = submit(pool, () -> engine.execute(INSERT));
                var independent = submit(pool, () -> engine.execute("INSERT INTO users (label) VALUES ('user');"));
                assertEquals(1, independent.get(2, TimeUnit.SECONDS).getId());
                assertFalse(blocked.isDone());
            } finally { firstLock.unlock(); pool.shutdown(); }
        }
    }

    @SuppressWarnings("unchecked")
    private TableState state(DbEngine engine, String tableName) throws Exception {
        return ((java.util.Map<String, TableState>) EngineTestAccess.field(engine, "tables")).get(tableName);
    }

    private void assertWholeRows(DbResult result) {
        assertTrue(result.isSuccess());
        for (var row : result.getRows()) {
            assertEquals("whole", row.get("name")); assertEquals(7, row.get("price"));
            assertTrue((Integer) row.get("id") >= 1);
        }
    }

    private <T> CompletableFuture<T> submit(DbWorkerPool pool, java.util.function.Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pool.execute(() -> {
            try { future.complete(task.get()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
}
