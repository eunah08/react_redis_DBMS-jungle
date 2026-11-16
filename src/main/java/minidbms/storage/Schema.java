package minidbms.storage;

import java.util.List;

/**
 * 테이블 정의. (협약 7.1)
 * columns의 맨 앞은 항상 id다. 스키마 파일에 적지 않아도 readSchema가 자동으로 붙인다.
 *
 * @param tableName 테이블 이름
 * @param columns   컬럼 목록 (id 포함)
 */
public record Schema(String tableName, List<Column> columns) {

    /** 모든 테이블의 첫 컬럼 이름. */
    public static final String ID_COLUMN_NAME = "id";

    /** 정수 컬럼의 최댓값. (협약 7.1) */
    public static final int MAX_INT_VALUE = Integer.MAX_VALUE;

    /**
     * 컬럼 이름으로 위치를 찾는다.
     *
     * @param columnName 컬럼 이름
     * @return 컬럼 위치 (id가 0). 없으면 -1
     */
    public int findColumnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).columnName().equals(columnName)) {
                return i;
            }
        }
        return -1;
    }
}
