package minidbms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import minidbms.engine.DbResult;

/**
 * 테스트가 함께 쓰는 도구. 임시 데이터 폴더를 만들고 결과를 JSON으로 비교하기 쉽게 바꾼다.
 */
public final class TestSupport {

    /** 저장소의 예제 products 스키마 경로. Maven은 프로젝트 폴더에서 테스트를 실행한다. */
    private static final Path PRODUCTS_SCHEMA = Path.of("data", "products.schema");

    private TestSupport() {
    }

    /**
     * 예제 products 스키마를 넣은 데이터 폴더를 준비한다.
     *
     * @param parentDir 임시 폴더
     * @return 데이터 폴더
     */
    public static Path prepareDataDir(Path parentDir) {
        try {
            Path dataDir = Files.createDirectories(parentDir.resolve("data"));
            Files.copy(PRODUCTS_SCHEMA, dataDir.resolve("products.schema"));
            return dataDir;
        } catch (IOException e) {
            throw new IllegalStateException("[prepareDataDir] 테스트 폴더를 준비하지 못했습니다", e);
        }
    }

    /**
     * 데이터 폴더에 스키마 파일을 쓴다.
     *
     * @param dataDir    데이터 폴더
     * @param tableName  테이블 이름
     * @param schemaText 스키마 내용
     */
    public static void writeSchema(Path dataDir, String tableName, String schemaText) {
        try {
            Files.writeString(dataDir.resolve(tableName + ".schema"), schemaText);
        } catch (IOException e) {
            throw new IllegalStateException("[writeSchema] 스키마를 쓰지 못했습니다", e);
        }
    }

    /**
     * 결과가 성공이 아니면 원인을 담아 실패시키고, 성공이면 그대로 돌려준다.
     *
     * @param result 엔진 결과
     * @return 같은 결과
     */
    public static DbResult requireSuccess(DbResult result) {
        if (result.isError()) {
            throw new AssertionError("성공해야 하는데 " + result.getErrorCode() + " : " + result.getMessage());
        }
        return result;
    }
}
