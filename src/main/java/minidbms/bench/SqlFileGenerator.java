package minidbms.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 공통 INSERT SQL을 만든다. 이름은 product_0000001부터 7자리 일련번호, price는 일련번호와 같다. (협약 12장)
 * 만든 큰 파일은 저장소에 올리지 않는다.
 */
final class SqlFileGenerator {

    private SqlFileGenerator() {
    }

    /**
     * @param number 일련번호 (1부터)
     * @return INSERT 문장 하나
     */
    static String insertSql(int number) {
        return "INSERT INTO products (name, price) VALUES ('" + BenchmarkRunner.productName(number)
                + "', " + number + ");";
    }

    /**
     * INSERT 문장을 한 줄에 하나씩 파일로 쓴다.
     *
     * @param path    쓸 파일 경로
     * @param records 건수 (0이면 빈 파일)
     * @throws IOException 파일을 쓰지 못했을 때
     */
    static void write(Path path, int records) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= records; i++) {
                writer.write(insertSql(i));
                writer.newLine();
            }
        }
    }
}
