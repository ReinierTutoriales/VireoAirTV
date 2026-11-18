/* libFuzzer target: binary plists, the body of most AirPlay requests. */
#include <stdint.h>
#include <stdlib.h>
#include "plist/plist.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    plist_t root = NULL;
    plist_from_bin((const char *) data, (uint32_t) size, &root);
    if (root) {
        char *xml = NULL;
        uint32_t len = 0;
        plist_to_xml(root, &xml, &len);
        plist_mem_free(xml);
        plist_free(root);
    }
    return 0;
}
