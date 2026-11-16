package minidbms.engine;

import minidbms.index.BPlusTree;
import minidbms.storage.Schema;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 테이블 하나의 공유 상태 : 스키마, B+ 트리, 다음 id, 락. (협약 9.3)
 * 다음 id와 B+ 트리와 broken 표시는 lock으로만 접근한다.
 * 쓰기 락 안에서 바꾸고, 읽기 락 안에서 읽는다.
 */
final class TableState {

    private final Schema schema;
    private final BPlusTree index = new BPlusTree();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long nextId = 1;
    private boolean isBroken;

    /**
     * @param schema 테이블 정의
     */
    TableState(Schema schema) {
        this.schema = schema;
    }

    Schema schema() {
        return schema;
    }

    BPlusTree index() {
        return index;
    }

    ReentrantReadWriteLock lock() {
        return lock;
    }

    long nextId() {
        return nextId;
    }

    void setNextId(long nextId) {
        this.nextId = nextId;
    }

    void advanceId() {
        nextId++;
    }

    boolean isBroken() {
        return isBroken;
    }

    /** 파일과 인덱스를 되돌리지 못했을 때 표시한다. 이후 이 테이블의 요청은 INTERNAL_ERROR다. */
    void markBroken() {
        isBroken = true;
    }
}
