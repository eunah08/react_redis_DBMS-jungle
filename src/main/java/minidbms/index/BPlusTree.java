package minidbms.index;

/**
 * 메모리 기반 B+ 트리. 키는 id, 값은 행의 위치다. (협약 9.2)
 * SQL과 파일을 모른다. 내부 노드는 키와 자식만, 리프 노드는 키와 값을 가지며 리프끼리 연결된다.
 * 삭제·병합·재분배는 구현하지 않는다. 노드 분할 방식과 내부 함수는 각자 정한다.
 */
public final class BPlusTree {

    /** 노드 하나의 키 최대 개수 기본값. 값은 각자 정한다. */
    public static final int BPLUS_TREE_ORDER = 64;

    private final int order;

    /**
     * 기본 차수로 빈 트리를 만든다.
     */
    public BPlusTree() {
        this(BPLUS_TREE_ORDER);
    }

    /**
     * @param order 노드 하나의 키 최대 개수. 테스트에서는 3 또는 4로 분할을 재현한다
     */
    public BPlusTree(int order) {
        this.order = order;
    }

    /**
     * 키와 값을 넣는다. 노드가 넘치면 나눈다.
     *
     * @param key   id
     * @param value 행의 위치
     */
    public void insertKey(int key, long value) {
        // TODO(이니셜): 같은 키를 두 번 넣으면 오류다 (협약 9.2)
        throw new UnsupportedOperationException("[insertKey] 아직 구현되지 않았습니다");
    }

    /**
     * 키로 값을 찾는다.
     *
     * @param key id
     * @return 행의 위치. 없으면 null
     */
    public Long findValue(int key) {
        // TODO(이니셜): 협약 9.2
        throw new UnsupportedOperationException("[findValue] 아직 구현되지 않았습니다");
    }

    /**
     * @return 들어 있는 키 개수
     */
    public int size() {
        // TODO(이니셜): 협약 9.2
        throw new UnsupportedOperationException("[size] 아직 구현되지 않았습니다");
    }

    /**
     * @return 트리 높이
     */
    public int height() {
        // TODO(이니셜): 협약 9.2
        throw new UnsupportedOperationException("[height] 아직 구현되지 않았습니다");
    }

    /**
     * @return 이 트리의 차수
     */
    public int getOrder() {
        return order;
    }
}
