package minidbms.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import minidbms.JsonCodec;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.engine.ErrorCode;
import minidbms.sql.SqlStatements;

/** SQL 파일을 순서대로 실행한다. 첫 실패에서 멈추며 앞선 성공은 보존한다. */
public final class CliMain {
    private record Options(Path dataDir, Path sqlFile, boolean isJson) { }
    private static final String DEFAULT_DATA_DIR = "data";
    private CliMain() { }

    /**
     * @param args [--data-dir 경로] [--json] SQL파일
     * @return 모두 성공/빈 파일은 0, 첫 오류는 stderr에 쓰고 1
     * 엔진과 파일 자원은 이 메서드가 종료한다. stdout/stderr는 닫지 않는다.
     */
    public static int run(String[] args) {
        boolean isJson = Arrays.asList(args).contains("--json");
        try { return executeFile(parseOptions(args)); }
        catch (IllegalArgumentException failure) {
            printError(DbResult.error(ErrorCode.SQL_SYNTAX, "[run] CLI 인자를 확인해 주세요"), isJson);
            return 1;
        } catch (IOException | RuntimeException failure) {
            printError(DbResult.error(ErrorCode.INTERNAL_ERROR, "[run] 파일을 처리할 수 없습니다"), isJson);
            return 1;
        }
    }

    private static Options parseOptions(String[] args) {
        Path dataDir = Path.of(DEFAULT_DATA_DIR);
        Path sqlFile = null;
        boolean isJson = false;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--json")) { isJson = true; }
            else if (args[index].equals("--data-dir")) {
                if (++index >= args.length || args[index].startsWith("--")) { throw new IllegalArgumentException(); }
                dataDir = Path.of(args[index]);
            } else if (args[index].startsWith("--") || sqlFile != null) {
                throw new IllegalArgumentException();
            } else { sqlFile = Path.of(args[index]); }
        }
        if (sqlFile == null) { throw new IllegalArgumentException(); }
        return new Options(dataDir, sqlFile, isJson);
    }

    private static int executeFile(Options options) throws IOException {
        List<String> statements = SqlStatements.split(Files.readString(options.sqlFile()));
        if (statements.isEmpty()) { return 0; }
        try (DbEngine engine = new DbEngine(options.dataDir())) {
            for (String sqlText : statements) {
                DbResult result = engine.execute(sqlText);
                if (!result.isSuccess()) { printError(result, options.isJson()); return 1; }
                if (options.isJson()) { System.out.println(toJson(result)); }
                else { printTable(result); }
            }
        }
        return 0;
    }

    private static String toJson(DbResult result) {
        return JsonCodec.toJson(result.getId() != null ? Map.of("id", result.getId())
                : Map.of("rows", result.getRows()));
    }

    private static void printError(DbResult result, boolean isJson) {
        System.err.println(isJson ? JsonCodec.toJson(Map.of("error", result.getErrorCode().name()))
                : result.getMessage());
    }

    private static void printTable(DbResult result) {
        if (result.getId() != null) { System.out.println("id: " + result.getId()); return; }
        List<Map<String, Object>> rows = result.getRows();
        if (!rows.isEmpty()) {
            System.out.println(String.join(" | ", rows.get(0).keySet()));
            System.out.println("----------------------------------------");
            for (Map<String, Object> row : rows) {
                System.out.println(String.join(" | ", row.values().stream().map(value -> value.toString()
                        .replace("\n", "\\n").replace("\r", "\\r")).toList()));
            }
        }
        System.out.println(rows.size() + (rows.size() == 1 ? " row" : " rows"));
    }
}
