package minidbms.sql;

/**
 * 토큰 하나.
 *
 * @param type     토큰 종류
 * @param text     키워드는 대문자, 문자열은 따옴표를 벗기고 ''를 '로 푼 값, 그 밖은 SQL에 적힌 글자
 * @param position 원문에서의 시작 위치 (0부터 센 글자 번호)
 */
record SqlToken(TokenType type, String text, int position) {
}
