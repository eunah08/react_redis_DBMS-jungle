package minidbms.sql;

/**
 * WHERE의 단일 동등 조건. (협약 7.3)
 *
 * @param columnName 조건 컬럼 이름
 * @param value      비교할 값. SQL에 적힌 모양 그대로 (따옴표로 감싼 값은 문자열, 아니면 정수)
 */
public record Condition(String columnName, Object value) {
}
