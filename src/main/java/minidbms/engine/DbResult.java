package minidbms.engine;

import minidbms.sql.StatementType;

import java.util.List;

/**
 * DbEngine.execute의 결과 객체. (협약 8.1)
 * 성공이면 INSERT는 새 id, SELECT는 컬럼 이름·행·접근 방식을 담고, 실패하면 오류 코드와 설명을 담는다.
 * 오류는 예외로 던지지 않고 이 객체로 돌려준다. (협약 10.4)
 * 출력 형식(표, JSON)은 여기서 만들지 않는다. cli·server가 바꾼다. (협약 6장)
 * 만든 뒤에는 바뀌지 않는다.
 */
public final class DbResult {

    private final StatementType type;
    private final int insertedId;
    private final List<String> columnNames;
    private final List<List<Object>> rows;
    private final AccessPath accessPath;
    private final ErrorCode errorCode;
    private final String errorMessage;

    private DbResult(StatementType type, int insertedId, List<String> columnNames, List<List<Object>> rows,
                     AccessPath accessPath, ErrorCode errorCode, String errorMessage) {
        this.type = type;
        this.insertedId = insertedId;
        this.columnNames = columnNames;
        this.rows = rows;
        this.accessPath = accessPath;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * @param id 새로 부여한 id
     * @return INSERT 성공 결과
     */
    public static DbResult forInsert(int id) {
        return new DbResult(StatementType.INSERT, id, List.of(), List.of(), null, null, null);
    }

    /**
     * @param columnNames 결과 컬럼 이름 (SELECT * 이면 id가 맨 앞인 스키마 순서)
     * @param rows        행 목록. 각 행은 columnNames와 순서가 같다. 정수는 Integer, 문자열은 String
     * @param accessPath  행을 찾은 방식
     * @return SELECT 성공 결과
     */
    public static DbResult forSelect(List<String> columnNames, List<List<Object>> rows, AccessPath accessPath) {
        return new DbResult(StatementType.SELECT, 0, columnNames, rows, accessPath, null, null);
    }

    /**
     * @param errorCode    오류 코드
     * @param errorMessage 사람이 읽는 설명 ("[함수이름] 한글 설명")
     * @return 실패 결과
     */
    public static DbResult forError(ErrorCode errorCode, String errorMessage) {
        return new DbResult(null, 0, List.of(), List.of(), null, errorCode, errorMessage);
    }

    /**
     * @return 실패한 결과이면 true
     */
    public boolean isError() {
        return errorCode != null;
    }

    /**
     * @return 문장 종류. 실패한 결과이면 null
     */
    public StatementType getType() {
        return type;
    }

    /**
     * @return INSERT가 부여한 id
     */
    public int getInsertedId() {
        return insertedId;
    }

    /**
     * @return SELECT 결과의 컬럼 이름
     */
    public List<String> getColumnNames() {
        return columnNames;
    }

    /**
     * @return SELECT 결과의 행 목록
     */
    public List<List<Object>> getRows() {
        return rows;
    }

    /**
     * @return 접근 방식. SELECT가 아니면 null. 공통 출력에는 넣지 않는다 (협약 8.1)
     */
    public AccessPath getAccessPath() {
        return accessPath;
    }

    /**
     * @return 오류 코드. 성공이면 null
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return 오류 설명. 성공이면 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
