package minidbms.cli;

/**
 * CLI 실행기. SQL 파일을 읽어 문장마다 DbEngine.execute를 부르고 표 또는 JSON으로 출력한다.
 * (협약 9.1, 10.1) 저장 파일을 직접 만지지 않는다. (협약 6장)
 */
public final class CliMain {

    private CliMain() {
    }

    /**
     * cli 명령을 실행한다.
     *
     * @param args "cli" 뒤의 인자. [--data-dir 경로] [--json] SQL파일
     * @return 모두 성공하면 0, 첫 오류에서 멈추면 1
     */
    public static int run(String[] args) {
        // TODO(이니셜): 인자 검증 → SQL 파일 읽기 → 문장 나누기 → 문장마다 execute → 출력 (협약 9.1)
        throw new UnsupportedOperationException("[run] cli 명령은 아직 구현되지 않았습니다");
    }
}
