package minidbms.storage;

import java.util.Iterator;

/**
 * 테이블 파일의 행을 처음부터 차례로 꺼내는 반복자. (협약 8.1 readRows)
 * 끝까지 읽으면 스스로 닫힌다. 중간에 멈출 때는 close를 불러야 하므로 try-with-resources로 쓴다.
 */
public interface RowCursor extends Iterator<StoredRow>, AutoCloseable {

    /**
     * 파일 자원을 닫는다. 여러 번 불러도 된다.
     */
    @Override
    void close();
}
