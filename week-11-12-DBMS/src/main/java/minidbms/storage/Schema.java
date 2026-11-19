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
}
