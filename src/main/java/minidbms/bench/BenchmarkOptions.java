package minidbms.bench;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 재실행 가능한 벤치마크 설정. 기본값은 협약 12장과 일치한다. */
final class BenchmarkOptions {
    private static final Set<String> OPTIONS = Set.of("--records", "--repeats", "--random-queries",
            "--seed", "--data-dir", "--mode", "--generate-sql", "--json");
    final int records;
    final int repeats;
    final int randomQueries;
    final long seed;
    final Path dataDir;
    final Path sqlOutput;
    final boolean isDirect;
    final boolean isJson;

    BenchmarkOptions(String[] args) {
        Map<String, String> options = parse(args);
        records = nonnegative(options.getOrDefault("--records", "100"));
        repeats = nonnegative(options.getOrDefault("--repeats", "1"));
        if (repeats == 0) { throw new IllegalArgumentException("[bench] 반복 수는 양수여야 합니다"); }
        randomQueries = nonnegative(options.getOrDefault("--random-queries", "3"));
        seed = Long.parseLong(options.getOrDefault("--seed", "42"));
        String mode = options.getOrDefault("--mode", "sql");
        if (!mode.equals("sql") && !mode.equals("direct")) { throw new IllegalArgumentException("[bench] 적재 모드"); }
        isDirect = mode.equals("direct");
        isJson = options.containsKey("--json");
        dataDir = options.containsKey("--data-dir") ? Path.of(options.get("--data-dir")) : null;
        sqlOutput = options.containsKey("--generate-sql") ? Path.of(options.get("--generate-sql")) : null;
    }

    private Map<String, String> parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String name = args[index];
            if (!OPTIONS.contains(name) || values.containsKey(name)) { throw new IllegalArgumentException("[bench] 옵션 오류"); }
            if (name.equals("--json")) { values.put(name, "true"); }
            else {
                if (++index >= args.length) { throw new IllegalArgumentException("[bench] 옵션 값 누락"); }
                values.put(name, args[index]);
            }
        }
        return values;
    }

    private int nonnegative(String value) {
        int number = Integer.parseInt(value);
        if (number < 0) { throw new IllegalArgumentException("[bench] 음수 건수"); }
        return number;
    }
}
