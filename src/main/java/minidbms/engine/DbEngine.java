package minidbms.engine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import minidbms.index.BPlusTree;
import minidbms.sql.SqlParser;
import minidbms.sql.SqlSyntaxException;
import minidbms.sql.Statement;
import minidbms.storage.RowCursor;
import minidbms.storage.Schema;
import minidbms.storage.StorageException;
import minidbms.storage.StoredRow;
import minidbms.storage.TableFile;
import minidbms.storage.TableNotFoundException;

/**
 * 공통 엔진 진입점. cli와 server는 execute 하나만 부른다. (협약 6장, 8.1)
 * 하는 일 : 파싱 결과 검증, 실행 경로 선택(인덱스 / 선형 탐색), 결과 생성.
 * 출력 형식과 HTTP 응답에 의존하지 않는다. System.exit를 부르지 않는다. (협약 2.1)
 *
 * <p>동시성 (협약 9.3) : 여러 스레드가 한 인스턴스를 공유한다. 테이블마다 읽기·쓰기 락이 있다.
 * SELECT는 읽기 락 안에서 결과를 완성하고, INSERT는 쓰기 락 안에서
 * id 부여 → 파일 추가 → 인덱스 등록 → (실패 시) 되돌리기까지 마친다.
 * 서로 다른 테이블은 서로 막지 않는다. 한 문장은 한 테이블만 잠그므로 락 순서 문제가 없다.
 */
public final class DbEngine {

    private static final String SCHEMA_GLOB = "*.schema";
    private static final String SCHEMA_SUFFIX = ".schema";

    private final Path dataDir;
    private final TableFile tableFile;
    private final Map<String, TableState> tables = new ConcurrentHashMap<>();
    private final Object loadMonitor = new Object();
    private volatile Runnable selectHook = () -> { };

    /**
     * 엔진을 연다. 데이터 디렉터리가 없으면 만들고, 스키마 파일이 있는 모든 테이블을 읽어
     * 테이블별 B+ 트리 인덱스를 다시 만든다. (협약 9.2) 열지 못한 테이블이 있어도 엔진은 열리고,
     * 그 테이블의 요청만 INTERNAL_ERROR가 된다.
     *
     * @param dataDir 데이터 디렉터리. 프로세스 하나만 사용한다 (협약 8.2)
     * @throws StorageException 데이터 디렉터리를 만들거나 읽을 수 없는 경우
     */
    public DbEngine(Path dataDir) {
        this.dataDir = dataDir;
        this.tableFile = new TableFile(dataDir);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException exception) {
            throw new StorageException("[DbEngine] 데이터 디렉터리를 만들 수 없습니다", exception);
        }
        loadAllTables();
    }

    /**
     * SQL 한 문장을 파싱·검증·실행한다. 사용자 입력 오류는 던지지 않고 오류 결과로 돌려준다.
     * 입력과 스키마 검증은 파일을 변경하기 전에 마친다. (협약 9.1)
     *
     * @param sqlText 세미콜론으로 끝나는 SQL 한 문장
     * @return 결과 객체 (조회 행 / 새 id / 오류 코드)
     */
    public DbResult execute(String sqlText) {
        return execute(sqlText, true);
    }

    /**
     * 인덱스 사용 여부를 정해 SQL 한 문장을 실행한다. 인덱스와 선형 탐색의 결과를 비교하는
     * 테스트와 벤치마크가 쓴다. cli와 server는 부르지 않는다.
     *
     * @param sqlText        세미콜론으로 끝나는 SQL 한 문장
     * @param isIndexAllowed false이면 WHERE id = ? 도 선형 탐색으로 실행한다
     * @return 결과 객체
     */
    public DbResult execute(String sqlText, boolean isIndexAllowed) {
        if (sqlText == null) {
            return DbResult.forError(ErrorCode.SQL_SYNTAX, "[execute] SQL이 없습니다");
        }
        Statement statement;
        try {
            statement = SqlParser.parseSql(sqlText);
        } catch (SqlSyntaxException exception) {
            return DbResult.forError(ErrorCode.SQL_SYNTAX, exception.getMessage());
        }
        return execute(statement, isIndexAllowed);
    }

    /**
     * 이미 파싱한 문장을 실행한다. 벤치마크가 파싱을 건너뛰고 엔진에 바로 넣을 때 쓴다. (협약 12장)
     *
     * @param statement      파싱 결과
     * @param isIndexAllowed false이면 WHERE id = ? 도 선형 탐색으로 실행한다
     * @return 결과 객체
     */
    public DbResult execute(Statement statement, boolean isIndexAllowed) {
        try {
            TableState table = getTable(statement.tableName());
            return switch (statement.type()) {
                case INSERT -> executeInsert(table, statement);
                case SELECT -> executeSelect(table, statement, isIndexAllowed);
            };
        } catch (EngineException exception) {
            return DbResult.forError(exception.getErrorCode(), exception.getMessage());
        } catch (StorageException exception) {
            return DbResult.forError(ErrorCode.INTERNAL_ERROR, exception.getMessage());
        } catch (RuntimeException exception) {
            return DbResult.forError(ErrorCode.INTERNAL_ERROR, "[execute] 예상하지 못한 오류: " + exception);
        }
    }

    /**
     * 테이블 파일 자원을 정리한다. 부른 뒤에는 이 엔진을 쓰지 않는다.
     */
    public void close() {
        tables.clear();
        tableFile.close();
    }

    /**
     * @return 데이터 디렉터리
     */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * 테이블 인덱스에 들어 있는 키 개수를 알려 준다. (점검·벤치마크용)
     *
     * @param tableName 테이블 이름
     * @return 키 개수
     * @throws IllegalArgumentException 열려 있지 않은 테이블인 경우
     */
    public int getIndexSize(String tableName) {
        TableState table = getOpenTable(tableName);
        return withReadLock(table, () -> table.index().size());
    }

    /**
     * 테이블 인덱스의 높이를 알려 준다. (점검·벤치마크용)
     *
     * @param tableName 테이블 이름
     * @return 트리 높이
     * @throws IllegalArgumentException 열려 있지 않은 테이블인 경우
     */
    public int getIndexHeight(String tableName) {
        TableState table = getOpenTable(tableName);
        return withReadLock(table, () -> table.index().height());
    }

    /**
     * 테스트 전용. SELECT가 읽기 락을 잡은 직후에 부를 동작을 정한다.
     * 두 SELECT가 읽기 구간에 함께 들어가는지 확인할 때 쓴다. (협약 11.2 Phase 3)
     *
     * @param hook 읽기 락 안에서 실행할 동작
     */
    public void setSelectHook(Runnable hook) {
        this.selectHook = Objects.requireNonNull(hook);
    }

    private void loadAllTables() {
        try (DirectoryStream<Path> schemaFiles = Files.newDirectoryStream(dataDir, SCHEMA_GLOB)) {
            for (Path schemaFile : schemaFiles) {
                String fileName = schemaFile.getFileName().toString();
                String tableName = fileName.substring(0, fileName.length() - SCHEMA_SUFFIX.length());
                if (TableFile.isValidTableName(tableName)) {
                    loadTableOrMarkBroken(tableName);
                }
            }
        } catch (IOException exception) {
            throw new StorageException("[DbEngine] 데이터 디렉터리를 읽을 수 없습니다", exception);
        }
    }

    private void loadTableOrMarkBroken(String tableName) {
        try {
            tables.put(tableName, loadTable(tableName));
        } catch (StorageException exception) {
            System.err.println("[loadAllTables] " + tableName + " 테이블을 열지 못했습니다: " + exception.getMessage());
            tables.put(tableName, TableState.broken(exception.getMessage()));
        }
    }

    /** 테이블 파일을 처음부터 읽어 인덱스를 만들고 다음 id를 정한다. */
    private TableState loadTable(String tableName) {
        Schema schema = tableFile.readSchema(tableName);
        BPlusTree index = new BPlusTree();
        long maxId = 0;
        try (RowCursor cursor = tableFile.readRows(tableName)) {
            while (cursor.hasNext()) {
                StoredRow storedRow = cursor.next();
                int id = (Integer) storedRow.row().get(0);
                index.insertKey(id, storedRow.position());
                maxId = Math.max(maxId, id);
            }
        } catch (IllegalArgumentException exception) {
            throw new StorageException("[loadTable] " + tableName + " 파일의 id가 겹쳐 인덱스를 만들 수 없습니다", exception);
        }
        return new TableState(schema, index, maxId + 1);
    }

    private TableState getTable(String tableName) {
        if (!TableFile.isValidTableName(tableName)) {
            throw new EngineException(ErrorCode.UNKNOWN_TABLE, "[getTable] 등록되지 않은 테이블입니다: " + tableName);
        }
        TableState table = tables.get(tableName);
        if (table == null) {
            table = loadMissingTable(tableName);
        }
        if (table.isBroken()) {
            throw new EngineException(ErrorCode.INTERNAL_ERROR, "[getTable] " + tableName
                    + " 테이블을 쓸 수 없습니다: " + table.failureMessage());
        }
        return table;
    }

    private TableState loadMissingTable(String tableName) {
        synchronized (loadMonitor) {
            TableState table = tables.get(tableName);
            if (table != null) {
                return table;
            }
            try {
                table = loadTable(tableName);
            } catch (TableNotFoundException exception) {
                throw new EngineException(ErrorCode.UNKNOWN_TABLE, exception.getMessage());
            }
            tables.put(tableName, table);
            return table;
        }
    }

    private TableState getOpenTable(String tableName) {
        TableState table = tables.get(tableName);
        if (table == null || table.isBroken()) {
            throw new IllegalArgumentException("[getOpenTable] 열려 있지 않은 테이블입니다: " + tableName);
        }
        return table;
    }

    private DbResult executeInsert(TableState table, Statement statement) {
        List<Object> values = StatementValidator.toInsertValues(table.schema(), statement);
        Lock writeLock = table.lock().writeLock();
        writeLock.lock();
        try {
            return appendRowAndIndex(table, values);
        } finally {
            writeLock.unlock();
        }
    }

    /** 쓰기 락 안에서 id 부여 → 파일 추가 → 인덱스 등록을 한 덩어리로 마친다. */
    private DbResult appendRowAndIndex(TableState table, List<Object> values) {
        checkUsable(table);
        long nextId = table.nextId();
        if (nextId > Schema.MAX_INT_VALUE) {
            throw new EngineException(ErrorCode.INTERNAL_ERROR, "[executeInsert] 더 부여할 id가 없습니다");
        }
        int id = (int) nextId;
        List<Object> row = new ArrayList<>(values.size() + 1);
        row.add(id);
        row.addAll(values);
        long position = tableFile.appendRow(table.schema().tableName(), row);
        registerIndex(table, id, position);
        table.advanceNextId();
        return DbResult.forInsert(id);
    }

    private void registerIndex(TableState table, int id, long position) {
        try {
            table.index().insertKey(id, position);
        } catch (RuntimeException exception) {
            undoAppend(table, position);
            throw new EngineException(ErrorCode.INTERNAL_ERROR,
                    "[executeInsert] 인덱스 등록에 실패해 행을 되돌렸습니다: " + exception.getMessage());
        }
    }

    private void undoAppend(TableState table, long position) {
        try {
            tableFile.rollbackAppend(table.schema().tableName(), position);
        } catch (RuntimeException exception) {
            table.markBroken("인덱스 등록 실패 뒤 파일을 되돌리지 못했습니다. 다시 시작해 복구해야 합니다");
            throw new EngineException(ErrorCode.INTERNAL_ERROR, "[executeInsert] " + table.failureMessage());
        }
    }

    private DbResult executeSelect(TableState table, Statement statement, boolean isIndexAllowed) {
        SelectPlan plan = StatementValidator.planSelect(table.schema(), statement);
        Lock readLock = table.lock().readLock();
        readLock.lock();
        try {
            selectHook.run();
            checkUsable(table);
            return selectRows(table, plan, isIndexAllowed);
        } finally {
            readLock.unlock();
        }
    }

    private DbResult selectRows(TableState table, SelectPlan plan, boolean isIndexAllowed) {
        boolean isIndexUsed = isIndexAllowed && plan.conditionIndex() == 0;
        List<List<Object>> rows = isIndexUsed ? findRowByIndex(table, plan) : scanRows(table, plan);
        AccessPath accessPath = isIndexUsed ? AccessPath.INDEX : AccessPath.FULL_SCAN;
        return DbResult.forSelect(plan.columnNames(), rows, accessPath);
    }

    private List<List<Object>> findRowByIndex(TableState table, SelectPlan plan) {
        if (plan.isEmptyByDefinition()) {
            return List.of();
        }
        int id = (Integer) plan.conditionValue();
        Long position = table.index().findValue(id);
        if (position == null) {
            return List.of();
        }
        List<Object> row = tableFile.readRowAt(table.schema().tableName(), position);
        if (!Objects.equals(row.get(0), id)) {
            throw new EngineException(ErrorCode.INTERNAL_ERROR, "[findRowByIndex] 인덱스가 가리키는 행의 id가 다릅니다: " + id);
        }
        return List.of(projectRow(row, plan));
    }

    private List<List<Object>> scanRows(TableState table, SelectPlan plan) {
        List<List<Object>> rows = new ArrayList<>();
        if (plan.isEmptyByDefinition()) {
            return rows;
        }
        try (RowCursor cursor = tableFile.readRows(table.schema().tableName())) {
            while (cursor.hasNext()) {
                List<Object> row = cursor.next().row();
                if (isMatch(row, plan)) {
                    rows.add(projectRow(row, plan));
                }
            }
        }
        return rows;
    }

    private static boolean isMatch(List<Object> row, SelectPlan plan) {
        return !plan.hasCondition() || Objects.equals(row.get(plan.conditionIndex()), plan.conditionValue());
    }

    private static List<Object> projectRow(List<Object> row, SelectPlan plan) {
        int[] columnIndexes = plan.columnIndexes();
        List<Object> projected = new ArrayList<>(columnIndexes.length);
        for (int columnIndex : columnIndexes) {
            projected.add(row.get(columnIndex));
        }
        return projected;
    }

    private static void checkUsable(TableState table) {
        if (table.isBroken()) {
            throw new EngineException(ErrorCode.INTERNAL_ERROR,
                    "[checkUsable] 테이블을 쓸 수 없습니다: " + table.failureMessage());
        }
    }

    private static <T> T withReadLock(TableState table, java.util.function.Supplier<T> action) {
        Lock readLock = table.lock().readLock();
        readLock.lock();
        try {
            return action.get();
        } finally {
            readLock.unlock();
        }
    }
}
