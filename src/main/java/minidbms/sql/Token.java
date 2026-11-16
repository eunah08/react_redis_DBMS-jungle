package minidbms.sql;

/**
 * SQL 문장을 나눈 조각 하나.
 * 문자열 토큰의 text는 따옴표를 벗기고 ''를 '로 바꾼 값이다.
 *
 * @param type     토큰 종류
 * @param text     토큰 내용
 * @param position SQL 문자열에서 시작한 위치 (0부터)
 */
record Token(TokenType type, String text, int position) {

    /**
     * @param symbol 비교할 기호
     * @return 이 토큰이 그 기호이면 true
     */
    boolean isSymbol(String symbol) {
        return type == TokenType.SYMBOL && text.equals(symbol);
    }

    /**
     * @param keyword 비교할 키워드 (대소문자 구분 없음)
     * @return 이 토큰이 그 키워드이면 true
     */
    boolean isKeyword(String keyword) {
        return type == TokenType.WORD && text.equalsIgnoreCase(keyword);
    }
}
