package minidbms.engine;

/**
 * SELECT가 행을 찾은 방식. 테스트와 벤치마크에서 인덱스를 탔는지 확인하는 용도다.
 * 공통 JSON·표 출력에는 넣지 않는다. (협약 8.1)
 */
public enum AccessPath {
    INDEX,
    FULL_SCAN
}
