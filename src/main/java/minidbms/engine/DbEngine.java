package minidbms.engine;

import minidbms.sql.SqlParser;
import minidbms.sql.SqlSyntaxException;
import minidbms.sql.Statement;
import minidbms.sql.StatementType;
import minidbms.storage.Schema;
import minidbms.storage.StoredRow;
import minidbms.storage.TableFile;
import minidbms.storage.TableNotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * 공통 엔진 진입점. cli와 server는 execute 하나만 부른다. (협약 6장, 8.1)
 * 하는 일 : 파싱 결과 검증, 실행 경로 선택(인덱스 / 선형 탐색), 결과 생성.
 * 출력 형식과 HTTP 응답에 의존하지 않는다. System.exit를 부르지 않는다. (협약 2.1)
 *
 * <p>동시성 (협약 9.3) : 인스턴스 하나를 여러 스레드가 함께 쓴다.
 * 테이블마다 ReentrantReadWriteLock이 있다. SELECT는 읽기 락, INSERT는 쓰기 락을 잡는다.
 * 쓰기 락은 "id 할당 → 파일 추가 → 인덱스 등록 → 실패 시 복구"까지 유지한다.
 * 한 문장은 한 테이블만 다루므로 락은 한 번에 하나만 잡고, 교착 상태가 생기지 않는다.
 */
public final class DbEngine {

    private final Path dataDir;
    private final TableFile tableFile;
    private final Map<String, TableState> tables = new ConcurrentHashMap<>();
    private final Runnable afterReadLockHook;

    /**
     * 엔진을 연다. 데이터 디렉터리의 모든 테이블 파일을 읽어 테이블별 인덱스를 다시 만든다. (협약 9.2)
     *
     * @param dataDir 데이터 디렉터리. 프로세스 하나만 사용한다 (협약 8.2)
     */
    public DbEngine(Path dataDir) {
        this(dataDir, () -> { });
    }

    /**
     * 테스트용 : 읽기 락을 잡은 직후 afterReadLockHook을 부른다. 두 SELECT가 읽기 구간에 함께 있는지 확인할 때 쓴다.
     */
    DbEngine(Path dataDir, Runnable afterReadLockHook) {
        this.dataDir = dataDir;
        this.tableFile = new TableFile(dataDir);
        this.afterReadLockHook = afterReadLockHook;
        loadExistingTables();
    }

    /**
     * SQL 한 문장을 파싱·검증·실행한다.
     * 입력과 스키마 검증은 파일을 변경하기 전에 마친다. (협약 9.1)
     * 사용자 입력 오류는 예외가 아니라 오류 결과로 돌려준다.
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 결과 객체 (조회 행 / 새 id / 오류 코드)
     */
    public DbResult execute(String sqlText) {
        return run(sqlText, true);
    }

    /**
     * execute와 같지만 WHERE id = ? 도 인덱스를 쓰지 않고 파일을 처음부터 읽는다.
     * 인덱스와 선형 탐색의 결과를 비교하는 테스트·벤치마크용이다. (협약 11.2, 12장)
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 결과 객체
     */
    public DbResult executeFullScan(String sqlText) {
        return run(sqlText, false);
    }

    /**
     * 이미 파싱된 문장을 실행한다. 파싱을 건너뛰고 싶은 벤치마크용이다. (협약 12장)
     *
     * @param statement 파싱 결과
     * @return 결과 객체
     */
    public DbResult executeStatement(Statement statement) {
        return runStatement(statement, true);
    }

    /**
     * 테이블 파일 자원을 정리한다.
     */
    public void close() {
        tableFile.close();
    }

    /**
     * @return 데이터 디렉터리
     */
    public Path getDataDir() {
        return dataDir;
    }

    private DbResult run(String sqlText, boolean isIndexAllowed) {
        try {
            return runStatement(SqlParser.parseSql(sqlText), isIndexAllowed);
        } catch (SqlSyntaxException e) {
            return DbResult.forError(ErrorCode.SQL_SYNTAX, e.getMessage());
        }
    }

    private DbResult runStatement(Statement statement, boolean isIndexAllowed) {
        try {
            TableState table = getTable(statement.tableName());
            if (statement.type() == StatementType.INSERT) {
                return executeInsert(statement.tableName(), table, statement);
            }
            return executeSelect(statement.tableName(), table, statement, isIndexAllowed);
        } catch (DbException e) {
            return DbResult.forError(e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            return DbResult.forError(ErrorCode.INTERNAL_ERROR, "[runStatement] 엔진 처리 중 오류가 발생했습니다");
        }
    }

    private TableState getTable(String tableName) {
        TableState table = tables.computeIfAbsent(tableName, this::loadTable);
        if (table == null) {
            throw new DbException(ErrorCode.UNKNOWN_TABLE, "[getTable] 등록되지 않은 테이블입니다: " + tableName);
        }
        return table;
    }

    private void loadExistingTables() {
        if (!Files.isDirectory(dataDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dataDir)) {
            files.map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".schema"))
                    .map(fileName -> fileName.substring(0, fileName.length() - ".schema".length()))
                    .sorted()
                    .forEach(this::loadTableOrWarn);
        } catch (IOException e) {
            throw new UncheckedIOException("[loadExistingTables] 데이터 디렉터리를 읽지 못했습니다", e);
        }
    }

    /** 열지 못하는 테이블이 있어도 엔진은 연다. 그 테이블은 처음 쓸 때 다시 열어 보고 INTERNAL_ERROR를 돌려준다. */
    private void loadTableOrWarn(String tableName) {
        try {
            tables.computeIfAbsent(tableName, this::loadTable);
        } catch (RuntimeException e) {
            System.err.println("[loadExistingTables] " + tableName + " 테이블을 열지 못했습니다: " + e.getMessage());
        }
    }

    /** 스키마를 읽고 테이블 파일을 처음부터 읽어 인덱스를 다시 만든다. 스키마가 없으면 null. */
    private TableState loadTable(String tableName) {
        Schema schema;
        try {
            schema = tableFile.readSchema(tableName);
        } catch (TableNotFoundException e) {
            return null;
        }
        TableState table = new TableState(schema);
        try {
            rebuildIndex(tableName, table);
        } catch (IllegalArgumentException e) {
            System.err.println("[loadTable] " + tableName + " 파일의 id가 겹쳐 인덱스를 만들 수 없습니다");
            table.markBroken();
        }
        return table;
    }

    private void rebuildIndex(String tableName, TableState table) {
        long maxId = 0;
        Iterator<StoredRow> rows = tableFile.readRows(tableName);
        while (rows.hasNext()) {
            StoredRow storedRow = rows.next();
            int id = (Integer) storedRow.row().get(StatementValidator.ID_COLUMN_INDEX);
            table.index().insertKey(id, storedRow.position());
            maxId = Math.max(maxId, id);
        }
        table.setNextId(maxId + 1);
    }

    private DbResult executeInsert(String tableName, TableState table, Statement statement) {
        ReentrantReadWriteLock.WriteLock writeLock = table.lock().writeLock();
        writeLock.lock();
        try {
            checkUsable(table);
            int id = takeNextId(table);
            List<Object> row = StatementValidator.buildInsertRow(table.schema(), statement, id);
            appendAndIndex(tableName, table, id, row);
            table.advanceId();
            return DbResult.forInsert(id);
        } finally {
            writeLock.unlock();
        }
    }

    private int takeNextId(TableState table) {
        if (table.nextId() > Integer.MAX_VALUE) {
            throw new DbException(ErrorCode.INTERNAL_ERROR, "[takeNextId] 더 부여할 id가 없습니다");
        }
        return (int) table.nextId();
    }

    private void appendAndIndex(String tableName, TableState table, int id, List<Object> row) {
        long position = tableFile.appendRow(tableName, row);
        try {
            table.index().insertKey(id, position);
        } catch (RuntimeException e) {
            rollbackInsert(tableName, table, position);
            throw new DbException(ErrorCode.INTERNAL_ERROR, "[appendAndIndex] 인덱스 등록에 실패해 행을 되돌렸습니다");
        }
    }

    private void rollbackInsert(String tableName, TableState table, long position) {
        try {
            tableFile.rollbackAppend(tableName, position);
        } catch (RuntimeException e) {
            table.markBroken();
        }
    }

    private DbResult executeSelect(String tableName, TableState table, Statement statement,
                                   boolean isIndexAllowed) {
        ReentrantReadWriteLock.ReadLock readLock = table.lock().readLock();
        readLock.lock();
        try {
            afterReadLockHook.run();
            checkUsable(table);
            return selectRows(tableName, table, statement, isIndexAllowed);
        } finally {
            readLock.unlock();
        }
    }

    private void checkUsable(TableState table) {
        if (table.isBroken()) {
            throw new DbException(ErrorCode.INTERNAL_ERROR,
                    "[checkUsable] 파일과 인덱스가 어긋나 이 테이블은 다시 시작할 때까지 쓸 수 없습니다");
        }
    }

    private DbResult selectRows(String tableName, TableState table, Statement statement, boolean isIndexAllowed) {
        Schema schema = table.schema();
        List<Integer> columnIndexes = StatementValidator.resolveSelectColumns(schema, statement.columns());
        ResolvedCondition condition = statement.hasWhere()
                ? StatementValidator.resolveCondition(schema, statement.condition()) : null;
        boolean isIndexLookup = isIndexAllowed && condition != null
                && condition.columnIndex() == StatementValidator.ID_COLUMN_INDEX;
        List<List<Object>> rows = isIndexLookup
                ? lookupById(tableName, table, condition, columnIndexes)
                : scanRows(tableName, condition, columnIndexes);
        AccessPath accessPath = isIndexLookup ? AccessPath.INDEX : AccessPath.FULL_SCAN;
        return DbResult.forSelect(namesOf(schema, columnIndexes), rows, accessPath);
    }

    /** WHERE id = ? : 인덱스로 위치를 찾고 그 행 하나만 파일에서 읽는다. (협약 9.2) */
    private List<List<Object>> lookupById(String tableName, TableState table, ResolvedCondition condition,
                                          List<Integer> columnIndexes) {
        if (condition.isImpossible()) {
            return List.of();
        }
        Long position = table.index().findValue((Integer) condition.value());
        if (position == null) {
            return List.of();
        }
        List<Object> row = tableFile.readRowAt(tableName, position);
        if (!row.get(StatementValidator.ID_COLUMN_INDEX).equals(condition.value())) {
            throw new DbException(ErrorCode.INTERNAL_ERROR, "[lookupById] 인덱스가 가리키는 행의 id가 다릅니다");
        }
        return List.of(project(row, columnIndexes));
    }

    /** 처음부터 끝까지 읽으며 조건에 맞는 행을 모은다. 조건이 없으면 모든 행이다. */
    private List<List<Object>> scanRows(String tableName, ResolvedCondition condition,
                                        List<Integer> columnIndexes) {
        List<List<Object>> rows = new ArrayList<>();
        if (condition != null && condition.isImpossible()) {
            return rows;
        }
        Iterator<StoredRow> storedRows = tableFile.readRows(tableName);
        while (storedRows.hasNext()) {
            List<Object> row = storedRows.next().row();
            if (condition == null || row.get(condition.columnIndex()).equals(condition.value())) {
                rows.add(project(row, columnIndexes));
            }
        }
        return rows;
    }

    private static List<Object> project(List<Object> row, List<Integer> columnIndexes) {
        List<Object> projected = new ArrayList<>(columnIndexes.size());
        for (int columnIndex : columnIndexes) {
            projected.add(row.get(columnIndex));
        }
        return projected;
    }

    private static List<String> namesOf(Schema schema, List<Integer> columnIndexes) {
        List<String> columnNames = new ArrayList<>(columnIndexes.size());
        for (int columnIndex : columnIndexes) {
            columnNames.add(schema.columns().get(columnIndex).columnName());
        }
        return columnNames;
    }
}
