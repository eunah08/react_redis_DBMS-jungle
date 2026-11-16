package minidbms.engine;

/**
 * 스키마로 검증하고 자료형을 맞춘 WHERE 조건.
 *
 * @param columnIndex 스키마에서 조건 컬럼의 번호 (id는 0)
 * @param value       비교할 값. 정수 컬럼이면 Integer, 문자열 컬럼이면 String. 일치할 수 없는 조건이면 null
 * @param isImpossible 음수 정수처럼 어떤 행과도 일치할 수 없으면 true (협약 7.2 : 빈 결과)
 */
record ResolvedCondition(int columnIndex, Object value, boolean isImpossible) {
}
