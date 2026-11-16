package minidbms.storage;

import java.util.List;

/**
 * 파일에서 읽은 행 하나와 그 위치. (협약 8.1 readRows : 행의 위치도 함께 돌려준다)
 *
 * @param position 행의 위치. 바이트 위치인지 행 번호인지는 각자 정한다 (협약 8.2)
 * @param row      컬럼 값 목록. 스키마의 컬럼 순서와 같고 맨 앞이 id다
 */
public record StoredRow(long position, List<Object> row) {
}
