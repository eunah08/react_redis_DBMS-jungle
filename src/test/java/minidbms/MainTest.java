package minidbms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 기본 틀이 빌드되고 테스트가 도는지 확인하는 시작 테스트.
 */
class MainTest {

    @Test
    void 인자가_없으면_사용법을_보여주고_1을_돌려준다() {
        assertEquals(1, Main.runCommand(new String[0]));
    }

    @Test
    void 모르는_명령이면_1을_돌려준다() {
        assertEquals(1, Main.runCommand(new String[] {"unknown"}));
    }
}
