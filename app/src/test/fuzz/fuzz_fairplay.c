/* libFuzzer target: /fp-setup records and the ekey of SETUP, before pairing. */
#include <stdint.h>
#include <string.h>
#include "fairplay.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 16 + 164 + 72) return 0;
    unsigned char req[16], msg[164], ekey[72], res[142], hres[32], key[16];
    memcpy(req, data, 16);
    memcpy(msg, data + 16, 164);
    memcpy(ekey, data + 180, 72);
    fairplay_t *fp = fairplay_init(NULL);
    fairplay_setup(fp, req, res);
    fairplay_handshake(fp, msg, hres);
    fairplay_decrypt(fp, ekey, key);
    fairplay_destroy(fp);
    return 0;
}
