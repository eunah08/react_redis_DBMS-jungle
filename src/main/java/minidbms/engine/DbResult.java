package minidbms.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 불변 실행 결과. 출력 형식과 파일 자원을 소유하지 않는다. */
public final class DbResult {
    private record Success(Integer id, List<Map<String, Object>> rows, AccessPath accessPath) { }
    private record Failure(ErrorCode code, String message) { }
    private final Success success;
    private final Failure failure;

    private DbResult(Success success, Failure failure) {
        this.success = success;
        this.failure = failure;
    }

    /** @return 새 id의 성공 결과. 상태/자원 변경 없음 */
    public static DbResult inserted(int id) {
        return new DbResult(new Success(id, List.of(), null), null);
    }

    /** 입력 행을 복사해 보관한다. @return 출력 순서가 보존된 불변 조회 결과 */
    public static DbResult selected(List<Map<String, Object>> rows, AccessPath accessPath) {
        List<Map<String, Object>> copies = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copies.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return new DbResult(new Success(null, List.copyOf(copies), accessPath), null);
    }

    /** @return 오류 코드와 경로를 숨긴 메시지. 상태/자원 변경 없음 */
    public static DbResult error(ErrorCode code, String message) {
        return new DbResult(null, new Failure(code, message));
    }

    /** @return 성공 여부 */
    public boolean isSuccess() { return failure == null; }
    /** @return INSERT id. 나머지는 null */
    public Integer getId() { return success == null ? null : success.id(); }
    /** @return 조회 행. INSERT/오류는 빈 목록 */
    public List<Map<String, Object>> getRows() { return success == null ? List.of() : success.rows(); }
    /** @return SELECT 접근 경로. 나머지는 null */
    public AccessPath getAccessPath() { return success == null ? null : success.accessPath(); }
    /** @return 오류 코드. 성공은 null */
    public ErrorCode getErrorCode() { return failure == null ? null : failure.code(); }
    /** @return 오류 메시지. 성공은 빈 문자열 */
    public String getMessage() { return failure == null ? "" : failure.message(); }
}
