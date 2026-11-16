package minidbms.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import minidbms.index.BPlusTree;
import minidbms.sql.SqlParseException;
import minidbms.sql.SqlParser;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;
import minidbms.storage.StorageException;
import minidbms.storage.StoredRow;
import minidbms.storage.TableFile;

/** SQL 처리와 파일/인덱스를 연결한다. JSON·HTTP를 모르며 TableFile 자원을 소유한다. */
public final class DbEngine implements AutoCloseable {
    private final TableFile tableFile;
    private final AccessPath readPath;
    private final Map<String, TableState> tables;
    private volatile boolean isClosed;

    /** 기존 파일로 인덱스를 복원한다. 호출자가 close해야 한다. 시작 I/O 실패는 예외다. */
    public DbEngine(Path dataDir) {
        this(dataDir, AccessPath.INDEX);
    }

    /**
     * 강제 FULL_SCAN은 Phase 1 회귀와 같은 SQL의 성능 비교에 사용한다.
     * @param dataDir 프로세스 하나가 사용하는 데이터 디렉터리
     * @param readPath id 조회에 인덱스를 쓸지 강제 순차 탐색할지 선택
     */
    public DbEngine(Path dataDir, AccessPath readPath) {
        this.tableFile = new TableFile(dataDir);
        this.readPath = Objects.requireNonNull(readPath);
        Map<String, TableState> loaded = new LinkedHashMap<>();
        try {
            for (String tableName : tableFile.tableNames()) { loaded.put(tableName, openTable(tableName)); }
        } catch (RuntimeException failure) {
            try { tableFile.close(); }
            catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        tables = Collections.unmodifiableMap(loaded);
    }

    private TableState openTable(String tableName) {
        TableState state;
        try { state = new TableState(tableFile.readSchema(tableName)); }
        catch (StorageException failure) {
            state = new TableState(null); state.isHealthy = false; return state;
        }
        try { rebuildTable(state); }
        catch (RuntimeException failure) { state.isHealthy = false; }
        return state;
    }

    /**
     * SQL 한 문장을 파싱·검증·실행한다. 실패 시 코드로 반환하며 프로세스를 종료하지 않는다.
     * @return 불변 행/id/오류와 접근 방식. 자원 종료 책임은 엔진에 남는다.
     */
    public DbResult execute(String sqlText) {
        return protect(() -> executeParsed(SqlParser.parseSql(sqlText)));
    }

    DbResult executeStatement(Statement statement) {
        return protect(() -> executeParsed(statement));
    }

    private DbResult protect(Supplier<DbResult> operation) {
        try { return operation.get(); }
        catch (SqlParseException failure) {
            return DbResult.error(failure.isInvalidValue() ? ErrorCode.INVALID_VALUE : ErrorCode.SQL_SYNTAX,
                    failure.getMessage());
        } catch (DbFailure failure) { return DbResult.error(failure.code(), failure.getMessage()); }
        catch (StorageException failure) {
            return DbResult.error(failure.isUnknownTable() ? ErrorCode.UNKNOWN_TABLE : ErrorCode.INTERNAL_ERROR,
                    failure.getMessage());
        } catch (RuntimeException failure) {
            return DbResult.error(ErrorCode.INTERNAL_ERROR, "[execute] 엔진 처리에 실패했습니다");
        }
    }

    private DbResult executeParsed(Statement statement) {
        TableState state = tables.get(statement.tableName());
        if (state == null) {
            throw new DbFailure(ErrorCode.UNKNOWN_TABLE, "execute", "등록되지 않은 테이블입니다");
        }
        Lock lock = statement.type() == StatementType.INSERT ? state.lock.writeLock() : state.lock.readLock();
        lock.lock();
        try {
            if (isClosed || !state.isHealthy) { throw internalFailure(); }
            return statement.type() == StatementType.INSERT ? insertRow(state, statement)
                    : selectRows(state, statement);
        } catch (Error failure) {
            state.isHealthy = false;
            throw failure;
        } finally { lock.unlock(); }
    }

    private DbResult insertRow(TableState state, Statement statement) {
        if (state.nextId > Integer.MAX_VALUE) { throw SchemaValidator.invalidValue(); }
        int id = (int) state.nextId;
        List<Object> row = SchemaValidator.insertRow(state.schema, statement, id);
        long position = appendSafely(state, row);
        try {
            if (state.index != null) { state.index.insertKey(id, position); }
        } catch (RuntimeException failure) {
            rollbackInsert(state, position);
            throw internalFailure();
        }
        state.nextId++;
        return DbResult.inserted(id);
    }

    private long appendSafely(TableState state, List<Object> row) {
        try { return tableFile.appendRow(state.schema.tableName(), row); }
        catch (StorageException failure) {
            restoreTable(state);
            throw failure;
        }
    }

    private void rollbackInsert(TableState state, long position) {
        try {
            tableFile.rollbackAppend(state.schema.tableName(), position);
            restoreTable(state);
        } catch (RuntimeException failure) { state.isHealthy = false; }
    }

    private void restoreTable(TableState state) {
        try { rebuildTable(state); state.isHealthy = true; }
        catch (RuntimeException failure) { state.isHealthy = false; }
    }

    private void rebuildTable(TableState state) {
        BPlusTree rebuilt = readPath == AccessPath.INDEX ? new BPlusTree() : null;
        long nextId = 1;
        Iterator<StoredRow> rows = tableFile.readRows(state.schema.tableName());
        while (rows.hasNext()) {
            StoredRow stored = rows.next();
            int id = (Integer) stored.row().get(0);
            if (id != nextId) { throw internalFailure(); }
            if (rebuilt != null) { rebuilt.insertKey(id, stored.position()); }
            nextId++;
        }
        state.index = rebuilt;
        state.nextId = nextId;
    }

    private DbResult selectRows(TableState state, Statement statement) {
        SchemaValidator.QueryPlan plan = SchemaValidator.planSelect(state.schema, statement);
        boolean hasIndex = state.index != null && plan.conditionIndex() == 0;
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (hasIndex) {
                Long position = state.index.findValue((Integer) plan.conditionValue());
                if (position != null) {
                    result.add(SchemaValidator.project(state.schema,
                            tableFile.readRowAt(state.schema.tableName(), position), plan.projection()));
                }
            } else { scanRows(state, plan, result); }
        } catch (StorageException failure) { state.isHealthy = false; throw failure; }
        return DbResult.selected(result, hasIndex ? AccessPath.INDEX : AccessPath.FULL_SCAN);
    }

    private void scanRows(TableState state, SchemaValidator.QueryPlan plan,
                          List<Map<String, Object>> result) {
        Iterator<StoredRow> rows = tableFile.readRows(state.schema.tableName());
        while (rows.hasNext()) {
            List<Object> row = rows.next().row();
            if (plan.conditionIndex() < 0 || Objects.equals(row.get(plan.conditionIndex()), plan.conditionValue())) {
                result.add(SchemaValidator.project(state.schema, row, plan.projection()));
                if (plan.conditionIndex() == 0) { return; }
            }
        }
    }

    private DbFailure internalFailure() {
        return new DbFailure(ErrorCode.INTERNAL_ERROR, "execute", "테이블 또는 파일 복구가 필요합니다");
    }

    /** 새 작업을 차단하고 각 테이블 작업이 끝난 뒤 채널을 닫는다. 여러 번 호출해도 된다. */
    @Override public void close() {
        if (isClosed) { return; }
        isClosed = true;
        for (TableState state : tables.values()) {
            state.lock.writeLock().lock();
            state.lock.writeLock().unlock();
        }
        tableFile.close();
    }

    /** @return 데이터 디렉터리. 상태 변경 없음 */
    public Path getDataDir() { return tableFile.getDataDir(); }
}
