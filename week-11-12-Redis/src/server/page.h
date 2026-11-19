#ifndef PAGE_H
#define PAGE_H

#include <stddef.h>

#include "http.h"

/*
 * 시각화 화면(visualization.html)을 서버가 직접 내려 준다. GET / 에 연결된다.
 * HTML은 빌드할 때 embed_html.py가 C 배열(build/visualization_data.c)로 바꿔 실행 파일에 넣는다.
 * 그래서 서버를 어느 폴더에서 실행해도 화면이 뜬다.
 */

/* embed_html.py가 만든다. 끝에 '\0'은 없다 */
extern const unsigned char visualization_html[];
extern const size_t visualization_html_len;

/*
 * 200 + text/html 응답을 채운다. 본문은 복사해서 넣는다.
 * 메모리 : 호출한 쪽이 http_response_destroy로 해제한다. 복사할 메모리가 없으면 500 INTERNAL_ERROR
 * 시간 복잡도 : O(HTML 크기)
 */
void page_write_response(http_response_t *out_response);

#endif
