package minidbms.engine;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import minidbms.index.BPlusTree;
import minidbms.storage.Schema;

/** 한 테이블의 스키마·다음 ID·인덱스. ID/인덱스는 쓰기 락, 장애 차단 표시는 volatile로 보호한다. */
final class TableState {
    final Schema schema;
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    BPlusTree index;
    long nextId = 1;
    volatile boolean isHealthy = true;

    TableState(Schema schema) {
        this.schema = schema;
    }
}
