package minidbms.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/** 실제 파일 I/O를 유지하고 선택한 실패/동기화 지점만 주입하는 테스트 도구다. */
public class ChannelDecorator extends FileChannel {
    protected final FileChannel delegate;
    public ChannelDecorator(FileChannel delegate) { this.delegate = delegate; }
    @Override public int read(ByteBuffer target) throws IOException { return delegate.read(target); }
    @Override public int read(ByteBuffer target, long position) throws IOException {
        return delegate.read(target, position);
    }
    @Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException {
        return delegate.read(targets, offset, length);
    }
    @Override public int write(ByteBuffer source) throws IOException { return delegate.write(source); }
    @Override public int write(ByteBuffer source, long position) throws IOException {
        return delegate.write(source, position);
    }
    @Override public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
        return delegate.write(sources, offset, length);
    }
    @Override public long position() throws IOException { return delegate.position(); }
    @Override public FileChannel position(long position) throws IOException {
        delegate.position(position); return this;
    }
    @Override public long size() throws IOException { return delegate.size(); }
    @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
    @Override public void force(boolean hasMetadata) throws IOException { delegate.force(hasMetadata); }
    @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return delegate.transferTo(position, count, target);
    }
    @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
        return delegate.transferFrom(source, position, count);
    }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return delegate.map(mode, position, size);
    }
    @Override public FileLock lock(long position, long size, boolean isShared) throws IOException {
        return delegate.lock(position, size, isShared);
    }
    @Override public FileLock tryLock(long position, long size, boolean isShared) throws IOException {
        return delegate.tryLock(position, size, isShared);
    }
    @Override protected void implCloseChannel() throws IOException { delegate.close(); }
}
