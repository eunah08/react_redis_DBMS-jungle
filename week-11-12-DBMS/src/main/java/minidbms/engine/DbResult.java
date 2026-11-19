package minidbms.engine;

/**
 * DbEngine.execute의 결과 객체. (협약 8.1)
 * 담아야 하는 것 : 조회한 행(SELECT) / 새 id(INSERT) / 오류 코드, 그리고 접근 방식(AccessPath).
 * 출력 형식(표, JSON)은 여기서 만들지 않는다. cli·server가 바꾼다. (협약 6장)
 * 필드 구성과 오류를 던질지 결과로 돌려줄지는 각자 정한다. (협약 10.4)
 */
public final class DbResult {

    // TODO(이니셜): 필드와 생성 방법을 정한다
}
