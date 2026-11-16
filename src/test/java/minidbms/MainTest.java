package minidbms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 기본 틀이 빌드되고 테스트가 도는지 확인하는 시작 테스트.
 */
class MainTest {

    @Test
    void missingCommandReturnsFailure() {
        assertEquals(1, Main.runCommand(new String[0]));
    }

    @Test
    void unknownCommandReturnsFailure() {
        assertEquals(1, Main.runCommand(new String[] {"unknown"}));
    }
}
