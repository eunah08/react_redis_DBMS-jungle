package minidbms.engine;

import java.util.List;

/**
 * 검증을 마친 SELECT. 컬럼 위치와 비교할 값을 미리 정해 둔다.
 *
 * @param columnNames       결과 컬럼 이름
 * @param columnIndexes     결과 컬럼의 스키마 위치 (id가 0)
 * @param conditionIndex    WHERE 컬럼의 스키마 위치. WHERE가 없으면 -1
 * @param conditionValue    비교할 값 (정수 컬럼은 Integer, 문자열 컬럼은 String)
 * @param isEmptyByDefinition 정수 범위를 벗어난 값이라 어떤 행도 일치할 수 없으면 true
 */
record SelectPlan(
        List<String> columnNames,
        int[] columnIndexes,
        int conditionIndex,
        Object conditionValue,
        boolean isEmptyByDefinition) {

    /** WHERE 컬럼이 없음을 뜻하는 위치. */
    static final int NO_CONDITION = -1;

    boolean hasCondition() {
        return conditionIndex != NO_CONDITION;
    }
}
