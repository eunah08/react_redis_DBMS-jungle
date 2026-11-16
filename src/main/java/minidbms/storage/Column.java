package minidbms.storage;

/**
 * 컬럼 하나의 정의. (협약 7.1)
 *
 * @param columnName 컬럼 이름 (소문자·숫자·밑줄)
 * @param type       자료형
 * @param maxBytes   문자열의 UTF-8 최대 바이트 수. 정수 컬럼이면 0
 */
public record Column(String columnName, ColumnType type, int maxBytes) {
}
