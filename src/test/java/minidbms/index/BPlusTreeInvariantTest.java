package minidbms.index;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BPlusTreeInvariantTest {
    private record WalkState(List<Object> leaves, List<Integer> depths) { }
    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void leavesHaveEqualDepthAndLinksVisitEveryKeyInOrder() throws Exception {
        for (int order : new int[] {3, 4, 64}) {
            BPlusTree tree = new BPlusTree(order);
            List<Integer> keys = new ArrayList<>();
            for (int id = 1; id <= 1000; id++) { keys.add(id); }
            Collections.shuffle(keys, new Random(42));
            keys.forEach(id -> tree.insertKey(id, id * 4096L));
            Object root = field(tree, "root");
            List<Object> leaves = new ArrayList<>();
            List<Integer> depths = new ArrayList<>();
            collectLeaves(root, 0, new WalkState(leaves, depths));
            assertEquals(1, depths.stream().distinct().count());
            assertEquals(tree.height(), depths.get(0) + 1);
            List<Integer> linkedKeys = walkLinks(leaves);
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 1000).boxed().toList(), linkedKeys);
        }
    }

    private void collectLeaves(Object node, int depth, WalkState state) throws Exception {
        if ((boolean) field(node, "isLeaf")) { state.leaves().add(node); state.depths().add(depth); return; }
        Object[] children = (Object[]) field(node, "children");
        int count = (int) field(node, "count");
        for (int index = 0; index <= count; index++) {
            assertNotNull(children[index]);
            collectLeaves(children[index], depth + 1, state);
        }
    }

    private List<Integer> walkLinks(List<Object> leaves) throws Exception {
        List<Integer> keys = new ArrayList<>();
        Object current = leaves.get(0);
        for (Object expected : leaves) {
            assertSame(expected, current);
            int[] leafKeys = (int[]) field(current, "keys");
            int count = (int) field(current, "count");
            for (int index = 0; index < count; index++) { keys.add(leafKeys[index]); }
            current = field(current, "next");
        }
        assertNull(current);
        return keys;
    }
}
