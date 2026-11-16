package minidbms.index;

import java.util.Arrays;

/**
 * id → 파일 위치를 저장하는 메모리 B+ 트리. 삭제·병합·재분배는 지원하지 않는다.
 * 내부 노드는 분기 키와 자식, 리프는 키와 위치 및 다음 리프를 가진다.
 * 스레드 안전성은 엔진의 테이블 잠금으로 보장한다.
 */
public final class BPlusTree {
    /** 노드 하나의 키 최대 개수. overflow 슬롯은 별도로 확보한다. */
    public static final int BPLUS_TREE_ORDER = 64;

    private static final class Node {
        private final boolean isLeaf;
        private final int[] keys;
        private final long[] values;
        private final Node[] children;
        private int count;
        private Node next;

        private Node(int order, boolean isLeaf) {
            this.isLeaf = isLeaf;
            keys = new int[order + 1];
            values = isLeaf ? new long[order + 1] : null;
            children = isLeaf ? null : new Node[order + 2];
        }
    }

    private record Split(int separator, Node right) { }

    private final int order;
    private Node root;
    private int size;

    /** 기본 차수의 빈 트리를 만든다. */
    public BPlusTree() {
        this(BPLUS_TREE_ORDER);
    }

    /**
     * @param order 노드당 최대 키 개수. 최소 3
     * @throws IllegalArgumentException 차수가 3보다 작을 때
     */
    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("[BPlusTree] 차수는 3 이상이어야 합니다");
        }
        this.order = order;
        root = new Node(order, true);
    }

    /**
     * O(log n). id와 파일 위치를 추가한다. 기존 키의 값은 변경하지 않는다.
     * @throws IllegalArgumentException 중복 키일 때
     */
    public void insertKey(int key, long value) {
        Split split = insert(root, key, value);
        if (split != null) {
            Node parent = new Node(order, false);
            parent.keys[0] = split.separator();
            parent.children[0] = root;
            parent.children[1] = split.right();
            parent.count = 1;
            root = parent;
        }
        size++;
    }

    /** O(log n). 없으면 null. 반환되는 Long은 불변 값이다. */
    public Long findValue(int key) {
        Node node = root;
        while (!node.isLeaf) {
            node = node.children[findChildIndex(node, key)];
        }
        int index = findKeyIndex(node, key);
        return index < node.count && node.keys[index] == key ? node.values[index] : null;
    }

    /** @return 저장된 키 개수, O(1) */
    public int size() {
        return size;
    }

    /** @return 비었으면 0, 루트 리프만 있으면 1. O(log n) */
    public int height() {
        if (size == 0) { return 0; }
        int height = 1;
        Node node = root;
        while (!node.isLeaf) {
            node = node.children[0];
            height++;
        }
        return height;
    }

    private Split insert(Node node, int key, long value) {
        if (node.isLeaf) {
            insertLeaf(node, key, value);
            return node.count > order ? splitLeaf(node) : null;
        }
        int index = findChildIndex(node, key);
        Split split = insert(node.children[index], key, value);
        if (split != null) { insertBranch(node, index, split); }
        return node.count > order ? splitBranch(node) : null;
    }

    private void insertLeaf(Node node, int key, long value) {
        int index = findKeyIndex(node, key);
        if (index < node.count && node.keys[index] == key) {
            throw new IllegalArgumentException("[insertKey] 중복된 id입니다");
        }
        System.arraycopy(node.keys, index, node.keys, index + 1, node.count - index);
        System.arraycopy(node.values, index, node.values, index + 1, node.count - index);
        node.keys[index] = key;
        node.values[index] = value;
        node.count++;
    }

    private void insertBranch(Node node, int index, Split split) {
        System.arraycopy(node.keys, index, node.keys, index + 1, node.count - index);
        System.arraycopy(node.children, index + 1, node.children, index + 2, node.count - index);
        node.keys[index] = split.separator();
        node.children[index + 1] = split.right();
        node.count++;
    }

    private Split splitLeaf(Node node) {
        int middle = node.count / 2;
        Node right = new Node(order, true);
        right.count = node.count - middle;
        System.arraycopy(node.keys, middle, right.keys, 0, right.count);
        System.arraycopy(node.values, middle, right.values, 0, right.count);
        node.count = middle;
        right.next = node.next;
        node.next = right;
        return new Split(right.keys[0], right);
    }

    private Split splitBranch(Node node) {
        int middle = node.count / 2;
        int separator = node.keys[middle];
        Node right = new Node(order, false);
        right.count = node.count - middle - 1;
        System.arraycopy(node.keys, middle + 1, right.keys, 0, right.count);
        System.arraycopy(node.children, middle + 1, right.children, 0, right.count + 1);
        Arrays.fill(node.children, middle + 1, node.count + 1, null);
        node.count = middle;
        return new Split(separator, right);
    }

    private int findKeyIndex(Node node, int key) {
        int low = 0;
        int high = node.count;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (node.keys[middle] < key) { low = middle + 1; }
            else { high = middle; }
        }
        return low;
    }

    private int findChildIndex(Node node, int key) {
        int low = 0;
        int high = node.count;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (node.keys[middle] <= key) { low = middle + 1; }
            else { high = middle; }
        }
        return low;
    }
}
