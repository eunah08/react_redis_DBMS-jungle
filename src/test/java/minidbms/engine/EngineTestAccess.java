package minidbms.engine;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.function.UnaryOperator;

/** 테스트 계측은 프로덕션 클래스에 넣지 않는다. 실제 파일 채널을 장식해 실패를 주입한다. */
final class EngineTestAccess {
    private EngineTestAccess() { }
    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    static TableState state(DbEngine engine, String tableName) throws Exception {
        return (TableState) ((Map<?, ?>) field(engine, "tables")).get(tableName);
    }
    static void decorateChannel(DbEngine engine, String tableName,
                                UnaryOperator<FileChannel> decorator) throws Exception {
        Object files = field(field(engine, "tableFile"), "files");
        Object rowFile = ((Map<?, ?>) files).get(tableName);
        Field channel = rowFile.getClass().getDeclaredField("channel");
        channel.setAccessible(true);
        channel.set(rowFile, decorator.apply((FileChannel) channel.get(rowFile)));
    }
}
