/* libFuzzer target: RTSP/HTTP requests from any host on the network (before pairing). */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "http_request.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 2) return 0;
    /* the first byte splits the input in two reads, as TCP may deliver it */
    size_t cut = 1 + (data[0] % (size - 1));
    http_request_t *r = http_request_init();
    http_request_add_data(r, (const char *) data + 1, cut - 1);
    if (!http_request_has_error(r)) http_request_add_data(r, (const char *) data + cut, size - cut);
    if (!http_request_has_error(r) && http_request_is_complete(r)) {
        http_request_get_method(r);
        http_request_get_url(r);
        http_request_get_protocol(r);
        http_request_get_header(r, "CSeq");
        http_request_get_header(r, "X-Apple-Session-ID");
        int len = 0;
        http_request_get_data(r, &len);
        char *hdr = NULL;
        http_request_get_header_string(r, &hdr);
        free(hdr);
    }
    http_request_destroy(r);
    return 0;
}
