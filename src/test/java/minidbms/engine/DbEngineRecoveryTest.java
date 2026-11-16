package minidbms.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidbms.storage.ChannelDecorator;

class DbEngineRecoveryTest {
    @TempDir Path dataDir;
    private static final String INSERT = "INSERT INTO products (name, price) VALUES ('x', 1);";

    @BeforeEach void schema() throws Exception {
        Files.writeString(dataDir.resolve("products.schema"), "name STRING 32\nprice INT\n");
    }

    @Test
    void indexRegistrationFailureRollsBackFileAndRebuildsIndex() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            assertEquals(1, engine.execute(INSERT).getId());
            long before = Files.size(dataDir.resolve("products.table"));
            EngineTestAccess.state(engine, "products").index.insertKey(2, 44L);
            assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute(INSERT).getErrorCode());
            assertEquals(before, Files.size(dataDir.resolve("products.table")));
            assertTrue(engine.execute("SELECT * FROM products WHERE id = 2;").getRows().isEmpty());
            assertEquals(2, engine.execute(INSERT).getId());
        }
    }

    @Test
    void partialWriteFailureKeepsEarlierRowsAndNextId() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            engine.execute(INSERT);
            long before = Files.size(dataDir.resolve("products.table"));
            EngineTestAccess.decorateChannel(engine, "products", channel -> new ChannelDecorator(channel) {
                private int writeCount;
                @Override public int write(ByteBuffer source, long position) throws IOException {
                    if (writeCount++ == 0) {
                        int limit = source.limit(); source.limit(source.position() + 2);
                        int written = delegate.write(source, position); source.limit(limit); return written;
                    }
                    if (writeCount == 2) { throw new IOException("injected partial write"); }
                    return delegate.write(source, position);
                }
            });
            assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute(INSERT).getErrorCode());
            assertEquals(before, Files.size(dataDir.resolve("products.table")));
            assertEquals(1, engine.execute("SELECT * FROM products;").getRows().size());
            assertEquals(2, engine.execute(INSERT).getId());
        }
    }

    @Test
    void rollbackFailureQuarantinesTableUntilRestartRebuildsIt() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            engine.execute(INSERT);
            EngineTestAccess.state(engine, "products").index.insertKey(2, 44L);
            EngineTestAccess.decorateChannel(engine, "products", channel -> new ChannelDecorator(channel) {
                @Override public FileChannel truncate(long size) throws IOException {
                    throw new IOException("injected rollback failure");
                }
            });
            assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute(INSERT).getErrorCode());
            assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute("SELECT * FROM products WHERE id = 999;").getErrorCode());
            assertEquals(ErrorCode.INTERNAL_ERROR, engine.execute(INSERT).getErrorCode());
        }
        try (DbEngine recovered = new DbEngine(dataDir)) {
            assertEquals(2, recovered.execute("SELECT * FROM products;").getRows().size());
            assertEquals(3, recovered.execute(INSERT).getId());
        }
    }
    @Test
    void errorDuringWriteQuarantinesTableBeforeContinuingOtherTasks() throws Exception {
        try (DbEngine engine = new DbEngine(dataDir)) {
            engine.execute(INSERT);
            EngineTestAccess.decorateChannel(engine, "products", channel -> new ChannelDecorator(channel) {
                @Override public int write(ByteBuffer source, long position) throws IOException {
                    int limit = source.limit(); source.limit(source.position() + 2);
                    delegate.write(source, position); source.limit(limit);
                    throw new AssertionError("injected write Error");
                }
            });
            assertThrows(AssertionError.class, () -> engine.execute(INSERT));
            assertEquals(ErrorCode.INTERNAL_ERROR,
                    engine.execute("SELECT * FROM products WHERE id = 0;").getErrorCode());
        }
    }
}
