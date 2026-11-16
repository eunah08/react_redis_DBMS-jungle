package minidbms.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 메모리 기반 B+ 트리. 키는 id, 값은 행의 위치다. (협약 9.2)
 * SQL과 파일을 모른다. 내부 노드는 키와 자식만, 리프 노드는 키와 값을 가지며 리프끼리 연결된다.
 * 삭제·병합·재분배는 구현하지 않는다.
 * 노드 분할 : 리프는 가운데에서 둘로 나누고 오른쪽 첫 키를 부모에 복사해 올린다.
 * 내부 노드는 가운데 키를 부모로 올리고(오른쪽에도 남기지 않음) 나눈다.
 * 스레드에 안전하지 않다. 같은 트리는 engine이 테이블 락 안에서만 접근한다. (협약 9.3)
 */
public final class BPlusTree {

    /** 노드 하나의 키 최대 개수 기본값. 값은 각자 정한다. */
    public static final int BPLUS_TREE_ORDER = 64;

    private static final int MIN_ORDER = 3;

    private final int order;
    private Node root = new LeafNode();
    private int size;
    private int height = 1;

    /** 모든 노드의 공통 부분. 키는 항상 오름차순이다. */
    private abstract static class Node {
        final List<Integer> keys = new ArrayList<>();

        abstract boolean isLeaf();
    }

    /** 리프 노드. keys와 values의 순서가 같고, next가 오른쪽 리프를 가리킨다. */
    private static final class LeafNode extends Node {
        private final List<Long> values = new ArrayList<>();
        private LeafNode next;

        @Override
        boolean isLeaf() {
            return true;
        }
    }

    /** 내부 노드. 자식 수는 키 수 + 1이다. i번째 자식은 keys[i-1] 이상, keys[i] 미만의 키를 가진다. */
    private static final class InternalNode extends Node {
        private final List<Node> children = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return false;
        }
    }

    /** 노드가 나뉘었을 때 부모에 알릴 내용. */
    private record SplitResult(int promotedKey, Node rightNode) {
    }

    /**
     * 기본 차수로 빈 트리를 만든다.
     */
    public BPlusTree() {
        this(BPLUS_TREE_ORDER);
    }

    /**
     * @param order 노드 하나의 키 최대 개수. 테스트에서는 3 또는 4로 분할을 재현한다
     * @throws IllegalArgumentException 차수가 3보다 작을 때
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
     * @throws IllegalArgumentException 같은 키가 이미 있을 때 (트리는 바뀌지 않는다)
     */
    public void insertKey(int key, long value) {
        SplitResult split = insertInto(root, key, value);
        size++;
        if (split != null) {
            growRoot(split);
        }
    }

    /**
     * 키로 값을 찾는다.
     *
     * @param key id
     * @return 행의 위치. 없으면 null
     */
    public Long findValue(int key) {
        LeafNode leaf = findLeaf(key);
        int position = Collections.binarySearch(leaf.keys, key);
        return position >= 0 ? leaf.values.get(position) : null;
    }

    /**
     * @return 들어 있는 키 개수
     */
    public int size() {
        return size;
    }

    /**
     * @return 트리 높이. 리프 하나뿐이면 1이다
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

    /** 테스트용 : 가장 왼쪽 리프부터 next를 따라가며 모은 키. 오름차순이어야 한다. */
    List<Integer> collectKeysByLeafChain() {
        List<Integer> keys = new ArrayList<>();
        Node node = root;
        while (!node.isLeaf()) {
            node = ((InternalNode) node).children.get(0);
        }
        for (LeafNode leaf = (LeafNode) node; leaf != null; leaf = leaf.next) {
            keys.addAll(leaf.keys);
        }
        return keys;
    }

    /** 테스트용 : 모든 리프의 깊이가 같으면 true. */
    boolean hasEqualLeafDepth() {
        return leafDepth(root) >= 0;
    }

    private int leafDepth(Node node) {
        if (node.isLeaf()) {
            return 1;
        }
        int expectedDepth = -1;
        for (Node child : ((InternalNode) node).children) {
            int depth = leafDepth(child);
            if (depth < 0 || (expectedDepth >= 0 && depth != expectedDepth)) {
                return -1;
            }
            expectedDepth = depth;
        }
        return expectedDepth + 1;
    }

    private LeafNode findLeaf(int key) {
        Node node = root;
        while (!node.isLeaf()) {
            InternalNode internal = (InternalNode) node;
            node = internal.children.get(findChildIndex(internal, key));
        }
        return (LeafNode) node;
    }

    private SplitResult insertInto(Node node, int key, long value) {
        if (node.isLeaf()) {
            return insertIntoLeaf((LeafNode) node, key, value);
        }
        return insertIntoInternal((InternalNode) node, key, value);
    }

    private SplitResult insertIntoLeaf(LeafNode leaf, int key, long value) {
        int position = Collections.binarySearch(leaf.keys, key);
        if (position >= 0) {
            throw new IllegalArgumentException("[insertKey] 이미 있는 키입니다: " + key);
        }
        int insertAt = -position - 1;
        leaf.keys.add(insertAt, key);
        leaf.values.add(insertAt, value);
        return leaf.keys.size() > order ? splitLeaf(leaf) : null;
    }

    private SplitResult insertIntoInternal(InternalNode node, int key, long value) {
        int childIndex = findChildIndex(node, key);
        SplitResult childSplit = insertInto(node.children.get(childIndex), key, value);
        if (childSplit == null) {
            return null;
        }
        node.keys.add(childIndex, childSplit.promotedKey());
        node.children.add(childIndex + 1, childSplit.rightNode());
        return node.keys.size() > order ? splitInternal(node) : null;
    }

    /** key가 들어갈 자식의 번호 : 노드의 키 중 key 이하인 것의 개수. */
    private int findChildIndex(InternalNode node, int key) {
        int position = Collections.binarySearch(node.keys, key);
        return position >= 0 ? position + 1 : -position - 1;
    }

    private SplitResult splitLeaf(LeafNode leaf) {
        int middle = leaf.keys.size() / 2;
        LeafNode right = new LeafNode();
        moveTail(leaf.keys, right.keys, middle);
        moveTail(leaf.values, right.values, middle);
        right.next = leaf.next;
        leaf.next = right;
        return new SplitResult(right.keys.get(0), right);
    }

    private SplitResult splitInternal(InternalNode node) {
        int middle = node.keys.size() / 2;
        int promotedKey = node.keys.get(middle);
        InternalNode right = new InternalNode();
        moveTail(node.keys, right.keys, middle + 1);
        moveTail(node.children, right.children, middle + 1);
        node.keys.remove(middle);
        return new SplitResult(promotedKey, right);
    }

    private void growRoot(SplitResult split) {
        InternalNode newRoot = new InternalNode();
        newRoot.keys.add(split.promotedKey());
        newRoot.children.add(root);
        newRoot.children.add(split.rightNode());
        root = newRoot;
        height++;
    }

    /** from의 fromIndex부터 끝까지를 to로 옮긴다. */
    private static <T> void moveTail(List<T> from, List<T> to, int fromIndex) {
        List<T> tail = from.subList(fromIndex, from.size());
        to.addAll(tail);
        tail.clear();
    }
}
