package minidbms.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BPlusTreeTest {
    @Test
    void emptyAndSingleLeafLookup() {
        BPlusTree tree = new BPlusTree(3);
        assertEquals(0, tree.size());
        assertNull(tree.findValue(42));
        tree.insertKey(42, 900L);
        tree.insertKey(-1, 0L);
        assertEquals(900L, tree.findValue(42));
        assertEquals(0L, tree.findValue(-1));
        assertNull(tree.findValue(41));
        assertEquals(2, tree.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 64})
    void repeatedRootAndInternalSplitsPreserveAllValues(int order) {
        List<Integer> keys = new ArrayList<>();
        for (int index = 0; index < 5000; index++) { keys.add(index); }
        verifyInsertionOrder(order, keys);
        Collections.reverse(keys);
        verifyInsertionOrder(order, keys);
        Collections.shuffle(keys, new Random(42));
        verifyInsertionOrder(order, keys);
    }

    private void verifyInsertionOrder(int order, List<Integer> keys) {
        BPlusTree tree = new BPlusTree(order);
        TreeMap<Integer, Long> expected = new TreeMap<>();
        for (int key : keys) {
            long position = key * 97L;
            tree.insertKey(key, position);
            expected.put(key, position);
            assertEquals(position, tree.findValue(key));
        }
        expected.forEach((key, value) -> assertEquals(value, tree.findValue(key)));
        assertEquals(expected.size(), tree.size());
        assertTrue(tree.height() >= 2);
        assertNull(tree.findValue(-1));
        assertNull(tree.findValue(5000));
    }

    @Test
    void duplicateRejectedWithoutChangingTree() {
        BPlusTree tree = new BPlusTree(3);
        for (int index = 0; index < 100; index++) { tree.insertKey(index, index * 8L); }
        assertThrows(IllegalArgumentException.class, () -> tree.insertKey(42, 99));
        assertEquals(100, tree.size());
        assertEquals(336L, tree.findValue(42));
        tree.insertKey(Integer.MIN_VALUE, Long.MIN_VALUE);
        tree.insertKey(Integer.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(Long.MIN_VALUE, tree.findValue(Integer.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, tree.findValue(Integer.MAX_VALUE));
    }
}
