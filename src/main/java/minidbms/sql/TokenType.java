package minidbms.sql;

/**
 * 토큰의 종류. (협약 18장 토큰)
 */
enum TokenType {
    /** 키워드 또는 이름. 예 : SELECT, products */
    WORD,
    /** 작은따옴표 문자열. 예 : '키보드' */
    STRING,
    /** 10진 정수. 예 : 35000, -1 */
    NUMBER,
    /** 기호. ( ) , ; * = */
    SYMBOL
}
