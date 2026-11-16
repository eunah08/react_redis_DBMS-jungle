package minidbms.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 협약 9.2, 11.2 (Phase 2 B+ 트리) 검증 : 분할, 순서별 삽입, 리프 연결, 균형, 중복 키.
 */
class BPlusTreeTest {

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> keys = new ArrayList<>();
        for (int key = from; key <= toInclusive; key++) {
            keys.add(key);
        }
        return keys;
    }

    private static BPlusTree insertAll(int order, List<Integer> keys) {
        BPlusTree tree = new BPlusTree(order);
        for (int key : keys) {
            tree.insertKey(key, key * 10L);
        }
        return tree;
    }

    private static void assertTreeHolds(BPlusTree tree, List<Integer> keys) {
        List<Integer> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        assertEquals(sorted.size(), tree.size());
        for (int key : sorted) {
            assertEquals(Long.valueOf(key * 10L), tree.findValue(key));
        }
        assertEquals(sorted, tree.collectKeysByLeafChain());
        assertTrue(tree.hasEqualLeafDepth());
    }

    @Test
    void 빈_트리는_높이_1이고_아무것도_찾지_못한다() {
        BPlusTree tree = new BPlusTree(3);

        assertEquals(0, tree.size());
        assertEquals(1, tree.height());
        assertNull(tree.findValue(1));
        assertEquals(List.of(), tree.collectKeysByLeafChain());
    }

    @Test
    void 작은_차수에서_오름차순_삽입_후_모든_키를_찾는다() {
        for (int order : List.of(3, 4, 5, 8)) {
            List<Integer> keys = range(1, 300);

            BPlusTree tree = insertAll(order, keys);

            assertTreeHolds(tree, keys);
            assertTrue(tree.height() > 2, "차수 " + order + "에서 높이가 3 이상이어야 합니다");
        }
    }

    @Test
    void 작은_차수에서_내림차순_삽입_후_모든_키를_찾는다() {
        for (int order : List.of(3, 4, 5, 8)) {
            List<Integer> keys = range(1, 300);
            Collections.reverse(keys);

            assertTreeHolds(insertAll(order, keys), keys);
        }
    }

    @Test
    void 작은_차수에서_무작위_삽입_후_모든_키를_찾는다() {
        for (int order : List.of(3, 4, 5, 8)) {
            List<Integer> keys = range(-500, 500);
            Collections.shuffle(keys, new Random(order));

            assertTreeHolds(insertAll(order, keys), keys);
        }
    }

    @Test
    void 없는_키는_null이다() {
        BPlusTree tree = insertAll(3, range(1, 100));

        assertNull(tree.findValue(0));
        assertNull(tree.findValue(-5));
        assertNull(tree.findValue(101));
        assertNull(tree.findValue(Integer.MAX_VALUE));
        assertNull(tree.findValue(Integer.MIN_VALUE));
    }

    @Test
    void 키_개수가_차수와_같을_때는_분할하지_않고_하나_더_넣으면_첫_분할이_일어난다() {
        BPlusTree tree = insertAll(3, range(1, 3));
        assertEquals(1, tree.height());

        tree.insertKey(4, 40L);

        assertEquals(2, tree.height());
        assertTreeHolds(tree, range(1, 4));
    }

    @Test
    void 높이가_바뀌는_순간_전후에도_모든_키를_찾는다() {
        BPlusTree tree = new BPlusTree(3);
        int previousHeight = 1;
        List<Integer> inserted = new ArrayList<>();

        for (int key = 1; key <= 100; key++) {
            tree.insertKey(key, key * 10L);
            inserted.add(key);
            if (tree.height() != previousHeight) {
                assertTreeHolds(tree, inserted);
                previousHeight = tree.height();
            }
        }

        assertTrue(previousHeight >= 4);
    }

    @Test
    void 첫_키와_마지막_키를_찾는다() {
        BPlusTree tree = insertAll(4, range(1, 1000));

        assertEquals(Long.valueOf(10L), tree.findValue(1));
        assertEquals(Long.valueOf(10_000L), tree.findValue(1000));
    }

    @Test
    void 같은_키를_두_번_넣으면_오류이고_트리는_바뀌지_않는다() {
        BPlusTree tree = insertAll(3, range(1, 50));

        assertThrows(IllegalArgumentException.class, () -> tree.insertKey(25, 999L));

        assertEquals(50, tree.size());
        assertEquals(Long.valueOf(250L), tree.findValue(25));
        assertTreeHolds(tree, range(1, 50));
    }

    @Test
    void 기본_차수로_십만_건을_넣고_찾는다() {
        BPlusTree tree = new BPlusTree();
        for (int key = 1; key <= 100_000; key++) {
            tree.insertKey(key, key * 10L);
        }

        assertEquals(BPlusTree.BPLUS_TREE_ORDER, tree.getOrder());
        assertEquals(100_000, tree.size());
        assertEquals(Long.valueOf(500_000L), tree.findValue(50_000));
        assertNull(tree.findValue(100_001));
        assertTrue(tree.height() <= 4);
        assertTrue(tree.hasEqualLeafDepth());
    }

    @Test
    void 차수가_3보다_작으면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(0));
    }
}
