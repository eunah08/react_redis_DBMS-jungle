package minidbms.engine;

import java.util.List;

/**
 * DbEngine.execute의 결과 객체. (협약 8.1)
 * 사용자 입력 오류는 예외로 던지지 않고 ERROR 결과로 돌려준다. (협약 10.4)
 * 출력 형식(표, JSON)은 여기서 만들지 않는다. cli·server가 바꾼다. (협약 6장)
 * 만든 뒤에는 바뀌지 않는다. 여러 스레드가 함께 읽어도 된다.
 */
public final class DbResult {

    /** 결과의 종류. */
    public enum Kind {
        INSERT,
        SELECT,
        ERROR
    }

    private final Kind kind;
    private final int insertedId;
    private final List<String> columnNames;
    private final List<List<Object>> rows;
    private final AccessPath accessPath;
    private final ErrorCode errorCode;
    private final String message;

    private DbResult(Kind kind, int insertedId, List<String> columnNames, List<List<Object>> rows,
                     AccessPath accessPath, ErrorCode errorCode, String message) {
        this.kind = kind;
        this.insertedId = insertedId;
        this.columnNames = columnNames;
        this.rows = rows;
        this.accessPath = accessPath;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * INSERT 성공 결과를 만든다.
     *
     * @param insertedId 새로 부여된 id
     * @return 결과
     */
    public static DbResult forInsert(int insertedId) {
        return new DbResult(Kind.INSERT, insertedId, List.of(), List.of(), null, null, null);
    }

    /**
     * SELECT 성공 결과를 만든다.
     *
     * @param columnNames 결과 컬럼 이름 (rows의 각 행과 순서가 같다)
     * @param rows        조회한 행. 정수는 Integer, 문자열은 String
     * @param accessPath  행을 찾은 방식
     * @return 결과
     */
    public static DbResult forSelect(List<String> columnNames, List<List<Object>> rows, AccessPath accessPath) {
        return new DbResult(Kind.SELECT, 0, List.copyOf(columnNames), rows, accessPath, null, null);
    }

    /**
     * 오류 결과를 만든다.
     *
     * @param errorCode 오류 코드
     * @param message   원인 설명 ("[함수이름] 한글 설명"). 사용자에게 보일지는 cli·server가 정한다
     * @return 결과
     */
    public static DbResult forError(ErrorCode errorCode, String message) {
        return new DbResult(Kind.ERROR, 0, List.of(), List.of(), null, errorCode, message);
    }

    /**
     * @return 결과 종류
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return 오류 결과이면 true
     */
    public boolean isError() {
        return kind == Kind.ERROR;
    }

    /**
     * @return INSERT가 부여한 id (INSERT 결과만 의미가 있다)
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
     * @return SELECT 결과의 행. 각 행은 컬럼 이름 순서와 같다
     */
    public List<List<Object>> getRows() {
        return rows;
    }

    /**
     * @return SELECT가 행을 찾은 방식. SELECT가 아니면 null
     */
    public AccessPath getAccessPath() {
        return accessPath;
    }

    /**
     * @return 오류 코드. 오류가 아니면 null
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return 오류 설명. 오류가 아니면 null
     */
    public String getMessage() {
        return message;
    }
}
