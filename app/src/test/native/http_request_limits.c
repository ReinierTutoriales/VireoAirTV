/* UxPlay patch 0030: request head and body are bounded.
 * Patch 0053 (llhttp 9.4.4): the RTSP and HTTP requests an iPhone sends still parse. */
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

    /* as sent by iOS: RTSP SETUP with a binary plist body, then an AirPlay video request */
    r = http_request_init();
    const char setup[] = "SETUP rtsp://192.168.1.2/1234 RTSP/1.0\r\nContent-Length: 4\r\n"
                         "Content-Type: application/x-apple-binary-plist\r\nCSeq: 3\r\n"
                         "DACP-ID: FDEBD2B3CFFA48D0\r\nActive-Remote: 2096196447\r\n"
                         "User-Agent: AirPlay/980.77.1\r\n\r\nbp\x00\x01";
    http_request_add_data(r, setup, sizeof(setup) - 1);
    if (http_request_has_error(r) || !http_request_is_complete(r)) return fail("RTSP SETUP rejected");
    if (strcmp(http_request_get_method(r), "SETUP") || strcmp(http_request_get_protocol(r), "RTSP/1.0")) return fail("RTSP SETUP parsed wrong");
    if (strcmp(http_request_get_header(r, "CSeq"), "3")) return fail("RTSP CSeq header");
    int len = 0;
    const char *body = http_request_get_data(r, &len);
    if (len != 4 || memcmp(body, "bp\x00\x01", 4)) return fail("RTSP binary body");
    http_request_destroy(r);

    r = http_request_init();
    const char *play = "POST /play HTTP/1.1\r\nX-Apple-Session-ID: 1F1E\r\nContent-Length: 0\r\n\r\n";
    http_request_add_data(r, play, strlen(play));
    if (http_request_has_error(r) || !http_request_is_complete(r)) return fail("POST /play rejected");
    if (strcmp(http_request_get_url(r), "/play")) return fail("POST /play url");
    http_request_destroy(r);

    free(chunk);
    puts("http_request_limits: ok");
    return 0;
}
