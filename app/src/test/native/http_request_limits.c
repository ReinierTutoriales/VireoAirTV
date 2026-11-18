/* UxPlay patch 0030: request head and body are bounded. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "http_request.h"

static int fail(const char *what) { fprintf(stderr, "FAIL: %s\n", what); return 1; }

int main(void) {
    char *chunk = calloc(1, 1 << 20);
    if (!chunk) return fail("alloc");

    http_request_t *r = http_request_init();
    const char *ok = "POST /ok HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello";
    http_request_add_data(r, ok, strlen(ok));
    if (http_request_has_error(r) || !http_request_is_complete(r)) return fail("normal request rejected");
    http_request_destroy(r);

    r = http_request_init();
    const char *big = "POST /big HTTP/1.1\r\nContent-Length: 40000000\r\n\r\n";
    http_request_add_data(r, big, strlen(big));
    for (int i = 0; i < 38 && !http_request_has_error(r); i++) http_request_add_data(r, chunk, 1 << 20);
    if (!http_request_has_error(r)) return fail("40 MB body accepted");
    http_request_destroy(r);

    r = http_request_init();
    const char *line = "GET / HTTP/1.1\r\nX: ";
    http_request_add_data(r, line, strlen(line));
    memset(chunk, 'a', 1 << 17);
    http_request_add_data(r, chunk, 1 << 17);
    if (!http_request_has_error(r)) return fail("128 KiB header accepted");
    http_request_destroy(r);

    free(chunk);
    puts("http_request_limits: ok");
    return 0;
}
