#include "page.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "api_errors.h"

#define PAGE_CONTENT_TYPE "text/html; charset=utf-8"

void page_write_response(http_response_t *out_response) {
    char *body = malloc(visualization_html_len);

    if (body == NULL) {
        fprintf(stderr, "[page_write_response] 화면을 복사할 메모리가 부족합니다\n");
        api_errors_write(API_ERROR_INTERNAL, "page_write_response", out_response);
        return;
    }
    memcpy(body, visualization_html, visualization_html_len);
    out_response->status_code = 200;
    out_response->body = body;
    out_response->body_len = visualization_html_len;
    out_response->content_type = PAGE_CONTENT_TYPE;
}
