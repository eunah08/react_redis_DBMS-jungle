package minidbms.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import minidbms.engine.DbEngine;
import minidbms.engine.DbResult;
import minidbms.json.JsonWriter;
import minidbms.sql.SqlSplitter;
import minidbms.storage.StorageException;

/**
 * CLI 실행기. SQL 파일을 읽어 문장마다 DbEngine.execute를 부르고 표 또는 JSON으로 출력한다.
 * (협약 9.1, 10.1) 저장 파일을 직접 만지지 않는다. (협약 6장)
 *
 * <p>정책 (README에도 적는다) : 문장은 위에서 아래로 실행하고 첫 오류에서 멈춘다.
 * 그 앞에서 성공한 INSERT는 파일에 남는다. 빈 파일, 주석뿐인 파일, 세미콜론뿐인 파일은
 * 실행할 문장이 없으므로 아무것도 출력하지 않고 종료 코드 0이다.
 * 없는 파일, 읽을 수 없는 파일, UTF-8이 아닌 파일은 오류 메시지와 종료 코드 1이다.
 */
public final class CliMain {

    private static final String DEFAULT_DATA_DIR = "data";
    private static final String OPTION_DATA_DIR = "--data-dir";
    private static final String OPTION_JSON = "--json";
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;
    private static final char BYTE_ORDER_MARK = '﻿';

    private CliMain() {
    }

    /**
     * cli 명령을 실행한다. 출력은 UTF-8로 System.out, System.err에 쓴다.
     *
     * @param args "cli" 뒤의 인자. [--data-dir 경로] [--json] SQL파일
     * @return 모두 성공하면 0, 첫 오류에서 멈추면 1
     */
    public static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream errorOutput = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        return run(args, out, errorOutput);
    }

    /**
     * 출력 위치를 정해 cli 명령을 실행한다. 테스트가 출력을 모을 때 쓴다.
     *
     * @param args "cli" 뒤의 인자
     * @param out         성공 결과를 쓸 곳
     * @param errorOutput 오류를 쓸 곳
     * @return 모두 성공하면 0, 첫 오류에서 멈추면 1
     */
    public static int run(String[] args, PrintStream out, PrintStream errorOutput) {
        CliOptions options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException exception) {
            errorOutput.println(exception.getMessage());
            errorOutput.println("사용법: java -jar target/mini-dbms.jar cli [--data-dir 경로] [--json] SQL파일");
            return EXIT_FAILURE;
        }
        String sqlText;
        try {
            sqlText = readSqlFile(options.sqlFile());
        } catch (IOException exception) {
            errorOutput.println(exception.getMessage());
            return EXIT_FAILURE;
        }
        CliOutput output = new CliOutput(out, errorOutput, options.isJson());
        return executeFile(options.dataDir(), SqlSplitter.splitStatements(sqlText), output);
    }

    private static int executeFile(Path dataDir, List<String> statements, CliOutput output) {
        DbEngine engine;
        try {
            engine = new DbEngine(dataDir);
        } catch (StorageException exception) {
            output.errorOutput().println(exception.getMessage());
            return EXIT_FAILURE;
        }
        try {
            return executeStatements(engine, statements, output);
        } finally {
            engine.close();
        }
    }

    private static int executeStatements(DbEngine engine, List<String> statements, CliOutput output) {
        for (int i = 0; i < statements.size(); i++) {
            DbResult result = engine.execute(statements.get(i));
            if (result.isError()) {
                printError(result, i + 1, output);
                return EXIT_FAILURE;
            }
            printResult(result, output);
        }
        return EXIT_SUCCESS;
    }

    private static void printResult(DbResult result, CliOutput output) {
        PrintStream out = output.out();
        if (output.isJson()) {
            out.println(JsonWriter.toJson(result));
        } else if (result.getKind() == DbResult.Kind.INSERT) {
            out.println("inserted id=" + result.getInsertedId());
        } else {
            TableFormatter.format(result.getColumnNames(), result.getRows()).forEach(out::println);
        }
        out.flush();
    }

    private static void printError(DbResult result, int statementNumber, CliOutput output) {
        PrintStream errorOutput = output.errorOutput();
        if (output.isJson()) {
            errorOutput.println(JsonWriter.errorToJson(result.getErrorCode()));
        } else {
            errorOutput.println("오류 " + result.getErrorCode() + " (" + statementNumber + "번째 문장) " + result.getMessage());
        }
        errorOutput.flush();
    }

    private static CliOptions parseOptions(String[] args) {
        Path dataDir = Path.of(DEFAULT_DATA_DIR);
        boolean isJson = false;
        Path sqlFile = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(OPTION_JSON)) {
                isJson = true;
            } else if (args[i].equals(OPTION_DATA_DIR)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("[parseOptions] --data-dir 뒤에 경로가 필요합니다");
                }
                dataDir = Path.of(args[++i]);
            } else if (args[i].startsWith("--")) {
                throw new IllegalArgumentException("[parseOptions] 알 수 없는 옵션입니다: " + args[i]);
            } else if (sqlFile != null) {
                throw new IllegalArgumentException("[parseOptions] SQL 파일은 하나만 줄 수 있습니다");
            } else {
                sqlFile = Path.of(args[i]);
            }
        }
        if (sqlFile == null) {
            throw new IllegalArgumentException("[parseOptions] SQL 파일 경로가 필요합니다");
        }
        return new CliOptions(dataDir, isJson, sqlFile);
    }

    private static String readSqlFile(Path sqlFile) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sqlFile);
        } catch (NoSuchFileException exception) {
            throw new IOException("[readSqlFile] SQL 파일이 없습니다: " + sqlFile, exception);
        } catch (IOException exception) {
            throw new IOException("[readSqlFile] SQL 파일을 읽을 수 없습니다: " + sqlFile, exception);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return text.isEmpty() || text.charAt(0) != BYTE_ORDER_MARK ? text : text.substring(1);
        } catch (CharacterCodingException exception) {
            throw new IOException("[readSqlFile] SQL 파일이 UTF-8이 아닙니다: " + sqlFile, exception);
        }
    }

    private record CliOptions(Path dataDir, boolean isJson, Path sqlFile) {
    }

    /** 결과와 오류를 어디에, 어떤 형식으로 쓸지. */
    private record CliOutput(PrintStream out, PrintStream errorOutput, boolean isJson) {
    }
}
