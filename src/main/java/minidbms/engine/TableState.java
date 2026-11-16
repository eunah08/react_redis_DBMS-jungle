package minidbms.engine;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import minidbms.index.BPlusTree;
import minidbms.storage.Schema;

/**
 * 열려 있는 테이블 하나의 공유 상태. (협약 9.3 : 공유 상태와 그것을 지키는 잠금)
 *
 * <p>지키는 규칙 : schema는 바뀌지 않는다. index와 nextId는 lock 안에서만 접근한다
 * (INSERT는 쓰기 락, SELECT는 읽기 락). failureMessage는 여러 스레드가 읽으므로 volatile이다.
 */
final class TableState {

    private final Schema schema;
    private final BPlusTree index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long nextId;
    private volatile String failureMessage;

    TableState(Schema schema, BPlusTree index, long nextId) {
        this.schema = schema;
        this.index = index;
        this.nextId = nextId;
    }

    /**
     * 열지 못한 테이블을 나타낸다. 이 테이블의 요청은 INTERNAL_ERROR가 된다.
     *
     * @param failureMessage 열지 못한 이유
     * @return 고장 난 상태
     */
    static TableState broken(String failureMessage) {
        TableState state = new TableState(null, null, 0);
        state.failureMessage = failureMessage;
        return state;
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

    void advanceNextId() {
        nextId++;
    }

    String failureMessage() {
        return failureMessage;
    }

    boolean isBroken() {
        return failureMessage != null;
    }

    void markBroken(String message) {
        this.failureMessage = message;
    }
}
