/* libplist patch 0001: payload copied through repeated references is bounded. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <plist/plist.h>

static void be(uint8_t *p, uint64_t v, int n) { for (int i = n - 1; i >= 0; i--) { p[i] = v & 0xff; v >>= 8; } }

/* object 0: array of n refs to object 1; object 1: data of s bytes */
static uint8_t *build(uint32_t n, uint32_t s, uint32_t *len) {
    uint32_t arr = 1 + 5 + n, dat = 1 + 5 + s, off = 8 + arr + dat;
    *len = off + 8 + 32;
    uint8_t *b = calloc(1, *len), *p = b;
    memcpy(p, "bplist00", 8); p += 8;
    *p++ = 0xAF; *p++ = 0x12; be(p, n, 4); p += 4; memset(p, 1, n); p += n;
    *p++ = 0x4F; *p++ = 0x12; be(p, s, 4); p += 4; memset(p, 'A', s); p += s;
    be(p, 8, 4); be(p + 4, 8 + arr, 4); p += 8;
    p[6] = 4; p[7] = 1; be(p + 8, 2, 8); be(p + 16, 0, 8); be(p + 24, off, 8);
    return b;
}

static int parse(uint32_t n, uint32_t s) {
    uint32_t len; uint8_t *b = build(n, s, &len);
    plist_t root = NULL;
    int ret = plist_from_bin((const char *)b, len, &root);
    plist_free(root); free(b);
    return ret;
}

int main(void) {
    if (parse(10, 10) != PLIST_ERR_SUCCESS) { fputs("FAIL: small plist rejected\n", stderr); return 1; }
    if (parse(1, 2000000) != PLIST_ERR_SUCCESS) { fputs("FAIL: single 2 MB blob rejected\n", stderr); return 1; }
    /* 1 MB input that would expand to 2 GB */
    if (parse(2000, 1000000) == PLIST_ERR_SUCCESS) { fputs("FAIL: 2 GB expansion accepted\n", stderr); return 1; }
    puts("bplist_expansion: ok");
    return 0;
}
