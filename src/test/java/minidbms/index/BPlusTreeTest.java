package minidbms.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 협약 11.2 Phase 2, 13장 인덱스 항목 : 작은 차수로 분할을 일으켜 구조를 검사한다.
 */
class BPlusTreeTest {

    @Test
    void 빈_트리는_높이_0이고_아무것도_찾지_못한다() {
        BPlusTree tree = new BPlusTree(3);

        assertEquals(0, tree.size());
        assertEquals(0, tree.height());
        assertNull(tree.findValue(1));
        assertEquals(List.of(), tree.keysInLeafOrder());
    }

    @Test
    void 차수가_3보다_작으면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 64})
    void 한_건_차수와_같은_건수_차수_더하기_1건에서_조회가_맞다(int order) {
        for (int count : new int[] {1, order, order + 1}) {
            BPlusTree tree = new BPlusTree(order);
            for (int key = 1; key <= count; key++) {
                tree.insertKey(key, key * 10L);
            }

            assertEquals(count, tree.size());
            assertEquals(count == order + 1 ? 2 : 1, tree.height());
            tree.checkInvariants();
            for (int key = 1; key <= count; key++) {
                assertEquals(key * 10L, tree.findValue(key));
            }
        }
    }

    @Test
    void 첫_분할은_리프_하나가_둘로_나뉘고_루트가_생긴다() {
        BPlusTree tree = new BPlusTree(3);
        tree.insertKey(1, 1);
        tree.insertKey(2, 2);
        tree.insertKey(3, 3);
        assertEquals(1, tree.height());

        tree.insertKey(4, 4);

        assertEquals(2, tree.height());
        assertEquals(List.of(1, 2, 3, 4), tree.keysInLeafOrder());
        tree.checkInvariants();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void 오름차순_삽입_뒤에_내부_노드와_루트가_분할된다(int order) {
        BPlusTree tree = new BPlusTree(order);
        int previousHeight = 0;
        int heightChanges = 0;
        for (int key = 1; key <= 200; key++) {
            tree.insertKey(key, key);
            if (tree.height() != previousHeight) {
                heightChanges++;
                previousHeight = tree.height();
            }
            tree.checkInvariants();
        }

        assertEquals(tree.height(), heightChanges);
        assertEquals(true, tree.height() >= 4);
        assertEquals(200, tree.size());
        assertAllFound(tree, 1, 200);
        assertNull(tree.findValue(0));
        assertNull(tree.findValue(201));
        assertNull(tree.findValue(-5));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void 내림차순_삽입도_구조가_유지된다(int order) {
        BPlusTree tree = new BPlusTree(order);
        for (int key = 200; key >= 1; key--) {
            tree.insertKey(key, key);
            tree.checkInvariants();
        }

        assertEquals(ascending(1, 200), tree.keysInLeafOrder());
        assertAllFound(tree, 1, 200);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 7})
    void 무작위_삽입도_구조가_유지되고_리프_연결이_오름차순이다(int order) {
        List<Integer> keys = ascending(1, 1000);
        Collections.shuffle(keys, new Random(20261116L));
        BPlusTree tree = new BPlusTree(order);

        for (int key : keys) {
            tree.insertKey(key, key * 2L);
        }

        tree.checkInvariants();
        assertEquals(ascending(1, 1000), tree.keysInLeafOrder());
        for (int key = 1; key <= 1000; key++) {
            assertEquals(key * 2L, tree.findValue(key));
        }
    }

    @Test
    void 같은_키를_두_번_넣으면_오류이고_트리는_그대로다() {
        BPlusTree tree = new BPlusTree(3);
        for (int key = 1; key <= 20; key++) {
            tree.insertKey(key, key);
        }

        assertThrows(IllegalArgumentException.class, () -> tree.insertKey(10, 99));

        assertEquals(20, tree.size());
        assertEquals(10L, tree.findValue(10));
        tree.checkInvariants();
    }

    @Test
    void 음수_키와_0도_다룬다() {
        BPlusTree tree = new BPlusTree(3);
        for (int key = -10; key <= 10; key++) {
            tree.insertKey(key, key + 100L);
        }

        assertEquals(90L, tree.findValue(-10));
        assertEquals(100L, tree.findValue(0));
        tree.checkInvariants();
    }

    @Test
    void 십만_건을_넣어도_기본_차수에서_높이가_낮다() {
        BPlusTree tree = new BPlusTree();
        for (int key = 1; key <= 100_000; key++) {
            tree.insertKey(key, key);
        }

        assertEquals(100_000, tree.size());
        assertEquals(true, tree.height() <= 4);
        tree.checkInvariants();
        assertEquals(77_777L, tree.findValue(77_777));
    }

    private static void assertAllFound(BPlusTree tree, int from, int to) {
        for (int key = from; key <= to; key++) {
            assertEquals((long) key, tree.findValue(key));
        }
    }

    private static List<Integer> ascending(int from, int to) {
        List<Integer> keys = new ArrayList<>();
        for (int key = from; key <= to; key++) {
            keys.add(key);
        }
        return keys;
    }
}
