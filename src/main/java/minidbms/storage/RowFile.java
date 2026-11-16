package minidbms.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** 테이블 파일 하나를 소유한다. 순차 읽기에도 요청별 버퍼와 위치를 사용한다. */
final class RowFile implements AutoCloseable {
    static final int HEADER_BYTES = 44;
    private static final long MAGIC = 0x4d494e4944423031L;
    private static final int PAGE_BYTES = 65536;
    private final FileChannel channel;
    private final RowCodec codec;
    private volatile long endPosition;
    private volatile boolean isBroken;

    private RowFile(FileChannel channel, Schema schema) {
        this.channel = channel;
        codec = new RowCodec(schema);
    }

    static RowFile open(Path path, Schema schema) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            RowFile rowFile = new RowFile(channel, schema);
            try { rowFile.readHeader(); return rowFile; }
            catch (IOException | RuntimeException failure) {
                try { channel.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        } catch (IOException failure) { throw new StorageException("openTable", failure); }
    }

    private void readHeader() throws IOException {
        endPosition = channel.size();
        if (endPosition == 0) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.putLong(MAGIC).putInt(codec.recordBytes()).put(codec.fingerprint()).flip();
            writeFully(header, 0);
            endPosition = HEADER_BYTES;
            return;
        }
        if (endPosition < HEADER_BYTES
                || (endPosition - HEADER_BYTES) % codec.recordBytes() != 0) {
            throw new IOException("불완전한 테이블 파일");
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        readFully(header, 0); header.flip();
        if (header.getLong() != MAGIC || header.getInt() != codec.recordBytes()) {
            throw new IOException("테이블 헤더 오류");
        }
        byte[] fingerprint = new byte[32]; header.get(fingerprint);
        if (!Arrays.equals(fingerprint, codec.fingerprint())) {
            throw new IOException("스키마 지문이 다릅니다");
        }
    }

    synchronized long appendRow(List<Object> row) {
        checkHealthy();
        ByteBuffer buffer = codec.encode(row);
        long position = endPosition;
        try {
            writeFully(buffer, position);
            endPosition += codec.recordBytes();
            return position;
        } catch (IOException failure) {
            try { channel.truncate(position); }
            catch (IOException rollbackFailure) {
                isBroken = true; failure.addSuppressed(rollbackFailure);
            }
            throw new StorageException("appendRow", failure);
        }
    }

    synchronized void rollbackAppend(long position) {
        checkHealthy();
        if (!isValidPosition(position) || position > endPosition) {
            throw new StorageException("rollbackAppend", new IllegalArgumentException("행 위치"));
        }
        try { channel.truncate(position); endPosition = position; }
        catch (IOException failure) {
            isBroken = true;
            throw new StorageException("rollbackAppend", failure);
        }
    }

    List<Object> readRowAt(long position) {
        checkHealthy();
        if (!isValidPosition(position) || position >= endPosition) {
            throw new StorageException("readRowAt", new IllegalArgumentException("행 위치"));
        }
        ByteBuffer buffer = ByteBuffer.allocate(codec.recordBytes());
        try { readFully(buffer, position); }
        catch (IOException failure) { throw new StorageException("readRowAt", failure); }
        buffer.flip();
        return codec.decode(buffer);
    }

    Iterator<StoredRow> readRows() {
        checkHealthy();
        long snapshotEnd = endPosition;
        return new Iterator<>() {
            private long position = HEADER_BYTES;
            private final ByteBuffer buffer = ByteBuffer.allocate(Math.max(codec.recordBytes(),
                    PAGE_BYTES / codec.recordBytes() * codec.recordBytes())).limit(0);

            @Override public boolean hasNext() { return position < snapshotEnd; }

            @Override public StoredRow next() {
                if (!hasNext()) { throw new NoSuchElementException(); }
                checkHealthy();
                if (buffer.remaining() < codec.recordBytes()) { refill(); }
                StoredRow storedRow = new StoredRow(position, codec.decode(buffer));
                position += codec.recordBytes();
                return storedRow;
            }

            private void refill() {
                buffer.clear().limit((int) Math.min(buffer.capacity(), snapshotEnd - position));
                try { readFully(buffer, position); }
                catch (IOException failure) { throw new StorageException("readRows", failure); }
                buffer.flip();
            }
        };
    }

    private boolean isValidPosition(long position) {
        return position >= HEADER_BYTES && (position - HEADER_BYTES) % codec.recordBytes() == 0;
    }

    private void checkHealthy() {
        if (isBroken) { throw new StorageException("rowFile", new IOException("복구가 필요합니다")); }
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int readBytes = channel.read(buffer, position);
            if (readBytes <= 0) { throw new EOFException("행 읽기가 중단되었습니다"); }
            position += readBytes;
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int writtenBytes = channel.write(buffer, position);
            if (writtenBytes <= 0) { throw new IOException("행 쓰기가 중단되었습니다"); }
            position += writtenBytes;
        }
    }

    @Override public void close() {
        try { channel.close(); }
        catch (IOException failure) { throw new StorageException("close", failure); }
    }
}
