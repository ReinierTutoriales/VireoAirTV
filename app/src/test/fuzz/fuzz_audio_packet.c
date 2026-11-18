/* libFuzzer target: RTP audio packets (AirPlay audio and mirroring audio) into the reorder buffer. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "raop_buffer.h"
#include "logger.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    static const unsigned char key[16] = {1}, iv[16] = {2};
    if (size < 2) return 0;
    logger_t *logger = logger_init();
    raop_buffer_t *buf = raop_buffer_init(logger, key, iv);
    /* several packets: each is preceded by a length byte */
    size_t pos = 0;
    while (pos + 1 < size) {
        size_t n = data[pos++];
        if (pos + n > size) n = size - pos;
        unsigned char pkt[256];
        memcpy(pkt, data + pos, n);
        raop_buffer_enqueue(buf, pkt, (unsigned short) n, 1);
        pos += n;
        unsigned int len = 0; uint32_t ts = 0; unsigned short seq = 0;
        void *payload;
        while ((payload = raop_buffer_dequeue(buf, &len, &ts, &seq, 1)) != NULL) free(payload);
    }
    raop_buffer_destroy(buf);
    logger_destroy(logger);
    return 0;
}
