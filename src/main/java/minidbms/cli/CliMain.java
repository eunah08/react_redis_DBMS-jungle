package minidbms.cli;

import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.json.JsonWriter;
import minidbms.sql.StatementType;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI 실행기. SQL 파일을 읽어 문장마다 DbEngine.execute를 부르고 표 또는 JSON으로 출력한다.
 * (협약 9.1, 10.1) 저장 파일을 직접 만지지 않는다. (협약 6장)
 *
 * <p>정책 (README에도 적는다) :
 * 문장이 실패하면 그 문장에서 멈추고 종료 코드 1을 돌려준다. 앞에서 성공한 INSERT는 파일에 남는다.
 * 빈 파일이나 공백·주석뿐인 파일은 아무것도 하지 않고 종료 코드 0이다.
 * 파일이 없거나 읽을 수 없으면 오류를 stderr에 쓰고 종료 코드 1이다.
 * 오류는 stderr에 쓴다. --json이면 오류도 JSON 한 줄이다.
 */
public final class CliMain {

    private static final String DEFAULT_DATA_DIR = "data";
    private static final String USAGE = "사용법: cli [--data-dir 경로] [--json] SQL파일";

    private CliMain() {
    }

    /** 명령 줄 옵션을 담는다. */
    private record CliOptions(Path dataDir, boolean isJson, Path sqlFile) {
    }

    /**
     * cli 명령을 실행한다.
     *
     * @param args "cli" 뒤의 인자. [--data-dir 경로] [--json] SQL파일
     * @return 모두 성공하면 0, 첫 오류에서 멈추면 1
     */
    public static int run(String[] args) {
        PrintStream out = new PrintStream(
                new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        return run(args, out, err);
    }

    /**
     * 출력 위치를 지정해 cli 명령을 실행한다. 테스트에서 쓴다.
     *
     * @param args "cli" 뒤의 인자
     * @param out  성공 결과를 쓸 곳
     * @param err  오류를 쓸 곳
     * @return 모두 성공하면 0, 아니면 1
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return runChecked(args, out, err);
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static int runChecked(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        List<String> statements;
        try {
            options = parseOptions(args);
            statements = SqlFileSplitter.split(readSqlFile(options.sqlFile()));
        } catch (IllegalArgumentException | IOException e) {
            err.println("[run] " + e.getMessage());
            return 1;
        }
        return executeAll(options, statements, out, err);
    }

    private static CliOptions parseOptions(String[] args) {
        Path dataDir = Path.of(DEFAULT_DATA_DIR);
        boolean isJson = false;
        Path sqlFile = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--json")) {
                isJson = true;
            } else if (args[i].equals("--data-dir")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--data-dir 뒤에 경로가 필요합니다. " + USAGE);
                }
                dataDir = Path.of(args[++i]);
            } else if (args[i].startsWith("--") || sqlFile != null) {
                throw new IllegalArgumentException("알 수 없는 인자입니다: " + args[i] + ". " + USAGE);
            } else {
                sqlFile = Path.of(args[i]);
            }
        }
        if (sqlFile == null) {
            throw new IllegalArgumentException("SQL 파일이 필요합니다. " + USAGE);
        }
        return new CliOptions(dataDir, isJson, sqlFile);
    }

    private static String readSqlFile(Path sqlFile) throws IOException {
        if (!Files.isRegularFile(sqlFile)) {
            throw new IOException("SQL 파일을 찾을 수 없습니다: " + sqlFile);
        }
        String fileText = Files.readString(sqlFile, StandardCharsets.UTF_8);
        return fileText.startsWith("﻿") ? fileText.substring(1) : fileText;
    }

    private static int executeAll(CliOptions options, List<String> statements, PrintStream out, PrintStream err) {
        DbEngine engine;
        try {
            engine = new DbEngine(options.dataDir());
        } catch (RuntimeException e) {
            err.println("[executeAll] 데이터 디렉터리를 열지 못했습니다: " + e.getMessage());
            return 1;
        }
        try {
            for (int i = 0; i < statements.size(); i++) {
                DbResult result = engine.execute(statements.get(i));
                if (result.isError()) {
                    printError(result, i + 1, options.isJson(), err);
                    return 1;
                }
                printSuccess(result, options.isJson(), out);
            }
            return 0;
        } finally {
            engine.close();
        }
    }

    private static void printSuccess(DbResult result, boolean isJson, PrintStream out) {
        if (isJson) {
            out.println(JsonWriter.toJson(result));
        } else if (result.getType() == StatementType.INSERT) {
            out.println("INSERT 완료 (id = " + result.getInsertedId() + ")");
        } else {
            TablePrinter.format(result.getColumnNames(), result.getRows()).forEach(out::println);
        }
    }

    private static void printError(DbResult result, int statementNumber, boolean isJson, PrintStream err) {
        if (isJson) {
            err.println(JsonWriter.toJson(result));
        } else {
            err.println("오류 [" + result.getErrorCode() + "] " + statementNumber + "번째 문장: "
                    + result.getErrorMessage());
        }
    }
}
