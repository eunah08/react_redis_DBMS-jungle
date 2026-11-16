package minidbms.storage;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 테이블 파일의 행을 처음부터 차례로 꺼내는 반복자. 한 번에 한 덩어리씩만 메모리에 올린다. (협약 8.1)
 * 만들 때의 파일 끝 위치까지만 읽는다. 따로 닫을 자원이 없다.
 */
final class RowIterator implements Iterator<StoredRow> {

    private final Schema schema;
    private final RowReader reader;
    private final long endPosition;
    private long nextPosition = RowCodec.HEADER_BYTES;

    /**
     * @param schema      테이블 정의
     * @param reader      이 반복자만 쓰는 읽기 도구
     * @param endPosition 읽을 수 있는 끝 위치
     */
    RowIterator(Schema schema, RowReader reader, long endPosition) {
        this.schema = schema;
        this.reader = reader;
        this.endPosition = endPosition;
    }

    @Override
    public boolean hasNext() {
        return nextPosition < endPosition;
    }

    @Override
    public StoredRow next() {
        if (!hasNext()) {
            throw new NoSuchElementException("[next] 더 읽을 행이 없습니다");
        }
        StoredRow row = reader.readRow(schema, nextPosition);
        nextPosition += reader.lastRecordBytes();
        return row;
    }
}
