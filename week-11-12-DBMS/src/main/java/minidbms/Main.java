package minidbms;

import minidbms.bench.BenchmarkMain;
import minidbms.cli.CliMain;
import minidbms.server.DbHttpServer;

import java.util.Arrays;

/**
 * 프로그램 진입점. 첫 번째 인자(cli / server / bench)를 보고 실행 명령을 고른다. (협약 10장)
 */
public final class Main {

    private static final String COMMAND_CLI = "cli";
    private static final String COMMAND_SERVER = "server";
    private static final String COMMAND_BENCH = "bench";
    private static final int EXIT_FAILURE = 1;

    private Main() {
    }

    /**
     * 프로세스를 시작한다. System.exit는 엔진 바깥인 여기서만 부른다. (협약 2.1)
     *
     * @param args 실행 인자. 첫 번째 값이 명령 이름이다
     */
    public static void main(String[] args) {
        int exitCode = runCommand(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 명령 이름에 맞는 실행기를 부른다.
     *
     * @param args 실행 인자
     * @return 종료 코드. 성공은 0, 실패는 1
     */
    public static int runCommand(String[] args) {
        if (args.length == 0) {
            printUsage();
            return EXIT_FAILURE;
        }
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case COMMAND_CLI:
                return CliMain.run(commandArgs);
            case COMMAND_SERVER:
                return DbHttpServer.run(commandArgs);
            case COMMAND_BENCH:
                return BenchmarkMain.run(commandArgs);
            default:
                printUsage();
                return EXIT_FAILURE;
        }
    }

    private static void printUsage() {
        System.err.println("사용법:");
        System.err.println("  java -jar target/mini-dbms.jar cli [--data-dir 경로] [--json] SQL파일");
        System.err.println("  java -jar target/mini-dbms.jar server --host 127.0.0.1 --port 8080"
                + " --workers 4 --queue 128 --data-dir data");
        System.err.println("  java -jar target/mini-dbms.jar bench --records 1000000");
    }
}
