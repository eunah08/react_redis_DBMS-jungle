package minidbms.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 메모리 기반 B+ 트리. 키는 id, 값은 행의 위치다. (협약 9.2)
 * SQL과 파일을 모른다. 내부 노드는 키와 자식만, 리프 노드는 키와 값을 가지며 리프끼리 연결된다.
 *
 * <p>구조 : 노드 하나는 키를 최대 {@code order}개 가진다. 내부 노드의 children[i]에는
 * keys[i-1] 이상, keys[i] 미만의 키가 들어 있다. 리프는 키를 정렬해 배열에 둔다.
 *
 * <p>분할 : 키가 order+1개가 되면 나눈다. 리프는 앞쪽 절반을 남기고 뒤쪽 절반을 새 리프로 옮긴 뒤
 * 새 리프의 첫 키를 부모에 복사해 올린다. 내부 노드는 가운데 키를 부모로 올리고(새 노드에 남기지 않는다)
 * 오른쪽 절반을 새 노드로 옮긴다. 루트가 나뉘면 새 루트를 만들어 높이가 1 늘어난다.
 *
 * <p>삭제·병합·재분배는 구현하지 않는다. (DELETE가 없다) 스레드 안전하지 않다.
 * 호출하는 쪽(engine)이 테이블 락 안에서만 접근한다. (협약 9.3)
 */
public final class BPlusTree {

    /** 노드 하나의 키 최대 개수 기본값. */
    public static final int BPLUS_TREE_ORDER = 64;

    private static final int MIN_ORDER = 3;

    private final int order;
    private Node root;
    private int size;
    private int height;

    /**
     * 기본 차수로 빈 트리를 만든다.
     */
    public BPlusTree() {
        this(BPLUS_TREE_ORDER);
    }

    /**
     * @param order 노드 하나의 키 최대 개수 (3 이상). 테스트에서는 3 또는 4로 분할을 재현한다
     * @throws IllegalArgumentException order가 3보다 작은 경우
     */
    public BPlusTree(int order) {
        if (order < MIN_ORDER) {
            throw new IllegalArgumentException("[BPlusTree] 차수는 " + MIN_ORDER + " 이상이어야 합니다: " + order);
        }
        this.order = order;
    }

    /**
     * 키와 값을 넣는다. 노드가 넘치면 나눈다.
     *
     * @param key   id
     * @param value 행의 위치
     * @throws IllegalArgumentException 이미 있는 키를 다시 넣는 경우 (id는 겹치지 않으므로 버그다)
     */
    public void insertKey(int key, long value) {
        if (root == null) {
            root = new Leaf(order);
            height = 1;
        }
        Split split = insert(root, key, value);
        if (split != null) {
            Internal newRoot = new Internal(order);
            newRoot.keys[0] = split.separator;
            newRoot.children[0] = root;
            newRoot.children[1] = split.right;
            newRoot.keyCount = 1;
            root = newRoot;
            height++;
        }
        size++;
    }

    /**
     * 키로 값을 찾는다.
     *
     * @param key id
     * @return 행의 위치. 없으면 null
     */
    public Long findValue(int key) {
        if (root == null) {
            return null;
        }
        Node node = root;
        while (node instanceof Internal internal) {
            node = internal.children[findChildIndex(internal, key)];
        }
        Leaf leaf = (Leaf) node;
        int position = Arrays.binarySearch(leaf.keys, 0, leaf.keyCount, key);
        return position >= 0 ? leaf.values[position] : null;
    }

    /**
     * @return 들어 있는 키 개수
     */
    public int size() {
        return size;
    }

    /**
     * @return 트리 높이. 빈 트리는 0, 리프 하나뿐이면 1
     */
    public int height() {
        return height;
    }

    /**
     * @return 이 트리의 차수
     */
    public int getOrder() {
        return order;
    }

    /**
     * 가장 왼쪽 리프부터 리프 연결을 따라가며 키를 모은다. 오름차순이어야 한다. (테스트·점검용)
     *
     * @return 리프 연결 순서의 키 목록
     */
    public List<Integer> keysInLeafOrder() {
        List<Integer> keys = new ArrayList<>(size);
        Node node = root;
        while (node instanceof Internal internal) {
            node = internal.children[0];
        }
        for (Leaf leaf = (Leaf) node; leaf != null; leaf = leaf.next) {
            for (int i = 0; i < leaf.keyCount; i++) {
                keys.add(leaf.keys[i]);
            }
        }
        return keys;
    }

    /**
     * 트리 구조가 올바른지 검사한다. (테스트·점검용)
     * 노드 안의 키가 정렬되어 있는지, 자식의 키가 부모 구분 키 범위 안인지,
     * 모든 리프의 깊이가 같은지, 리프 연결이 모든 키를 오름차순으로 지나는지 본다.
     *
     * @throws IllegalStateException 규칙이 깨진 경우
     */
    public void checkInvariants() {
        if (root == null) {
            return;
        }
        int leafDepth = checkNode(root, new KeyRange(Long.MIN_VALUE, Long.MAX_VALUE), 1);
        if (leafDepth != height) {
            throw new IllegalStateException("[checkInvariants] 높이(" + height + ")와 리프 깊이(" + leafDepth + ")가 다릅니다");
        }
        List<Integer> keys = keysInLeafOrder();
        if (keys.size() != size) {
            throw new IllegalStateException("[checkInvariants] 리프 연결의 키 수(" + keys.size() + ")가 size(" + size + ")와 다릅니다");
        }
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i - 1) >= keys.get(i)) {
                throw new IllegalStateException("[checkInvariants] 리프 연결이 오름차순이 아닙니다");
            }
        }
    }

    private Split insert(Node node, int key, long value) {
        if (node instanceof Leaf leaf) {
            return insertIntoLeaf(leaf, key, value);
        }
        Internal internal = (Internal) node;
        int childIndex = findChildIndex(internal, key);
        Split childSplit = insert(internal.children[childIndex], key, value);
        if (childSplit == null) {
            return null;
        }
        return insertIntoInternal(internal, childIndex, childSplit);
    }

    private Split insertIntoLeaf(Leaf leaf, int key, long value) {
        int found = Arrays.binarySearch(leaf.keys, 0, leaf.keyCount, key);
        if (found >= 0) {
            throw new IllegalArgumentException("[insertKey] 이미 있는 키입니다: " + key);
        }
        int position = -(found + 1);
        shiftRight(leaf.keys, position, leaf.keyCount);
        shiftRight(leaf.values, position, leaf.keyCount);
        leaf.keys[position] = key;
        leaf.values[position] = value;
        leaf.keyCount++;
        return leaf.keyCount > order ? splitLeaf(leaf) : null;
    }

    private Split insertIntoInternal(Internal internal, int childIndex, Split childSplit) {
        shiftRight(internal.keys, childIndex, internal.keyCount);
        shiftRight(internal.children, childIndex + 1, internal.keyCount + 1);
        internal.keys[childIndex] = childSplit.separator;
        internal.children[childIndex + 1] = childSplit.right;
        internal.keyCount++;
        return internal.keyCount > order ? splitInternal(internal) : null;
    }

    private Split splitLeaf(Leaf leaf) {
        int leftCount = (leaf.keyCount + 1) / 2;
        Leaf right = new Leaf(order);
        int rightCount = leaf.keyCount - leftCount;
        System.arraycopy(leaf.keys, leftCount, right.keys, 0, rightCount);
        System.arraycopy(leaf.values, leftCount, right.values, 0, rightCount);
        right.keyCount = rightCount;
        leaf.keyCount = leftCount;
        right.next = leaf.next;
        leaf.next = right;
        return new Split(right.keys[0], right);
    }

    private Split splitInternal(Internal internal) {
        int middle = internal.keyCount / 2;
        int separator = internal.keys[middle];
        Internal right = new Internal(order);
        int rightKeyCount = internal.keyCount - middle - 1;
        System.arraycopy(internal.keys, middle + 1, right.keys, 0, rightKeyCount);
        System.arraycopy(internal.children, middle + 1, right.children, 0, rightKeyCount + 1);
        Arrays.fill(internal.children, middle + 1, internal.keyCount + 1, null);
        right.keyCount = rightKeyCount;
        internal.keyCount = middle;
        return new Split(separator, right);
    }

    /** key 이하인 구분 키의 개수 = 따라가야 할 자식의 위치. */
    private static int findChildIndex(Internal internal, int key) {
        int found = Arrays.binarySearch(internal.keys, 0, internal.keyCount, key);
        return found >= 0 ? found + 1 : -(found + 1);
    }

    private static void shiftRight(int[] array, int from, int count) {
        System.arraycopy(array, from, array, from + 1, count - from);
    }

    private static void shiftRight(long[] array, int from, int count) {
        System.arraycopy(array, from, array, from + 1, count - from);
    }

    private static void shiftRight(Object[] array, int from, int count) {
        System.arraycopy(array, from, array, from + 1, count - from);
    }

    private int checkNode(Node node, KeyRange range, int depth) {
        for (int i = 0; i < node.keyCount; i++) {
            boolean isOutOfRange = node.keys[i] < range.lowInclusive() || node.keys[i] >= range.highExclusive();
            boolean isUnsorted = i > 0 && node.keys[i - 1] >= node.keys[i];
            if (isOutOfRange || isUnsorted || node.keyCount > order) {
                throw new IllegalStateException("[checkInvariants] 노드의 키 순서나 범위가 올바르지 않습니다");
            }
        }
        if (node instanceof Leaf) {
            return depth;
        }
        return checkChildren((Internal) node, range, depth);
    }

    private int checkChildren(Internal internal, KeyRange range, int depth) {
        int leafDepth = -1;
        for (int i = 0; i <= internal.keyCount; i++) {
            long low = i == 0 ? range.lowInclusive() : internal.keys[i - 1];
            long high = i == internal.keyCount ? range.highExclusive() : internal.keys[i];
            int childDepth = checkNode(internal.children[i], new KeyRange(low, high), depth + 1);
            if (leafDepth != -1 && leafDepth != childDepth) {
                throw new IllegalStateException("[checkInvariants] 리프 깊이가 서로 다릅니다");
            }
            leafDepth = childDepth;
        }
        return leafDepth;
    }

    private record Split(int separator, Node right) {
    }

    /** 한 노드 아래에 올 수 있는 키의 범위. 점검용. */
    private record KeyRange(long lowInclusive, long highExclusive) {
    }

    private abstract static class Node {
        final int[] keys;
        int keyCount;

        Node(int order) {
            this.keys = new int[order + 1];
        }
    }

    private static final class Leaf extends Node {
        final long[] values;
        Leaf next;

        Leaf(int order) {
            super(order);
            this.values = new long[order + 1];
        }
    }

    private static final class Internal extends Node {
        final Node[] children;

        Internal(int order) {
            super(order);
            this.children = new Node[order + 2];
        }
    }
}
