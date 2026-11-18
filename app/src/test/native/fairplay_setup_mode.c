/* UxPlay patch 0044: the FairPlay key mode from the network must not index past reply_message[4]. */
#include <stdio.h>
#include <string.h>
#include "fairplay.h"

int main(void) {
    fairplay_t *fp = fairplay_init(NULL);
    if (!fp) { fputs("FAIL: init\n", stderr); return 1; }
    unsigned char req[16] = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0, 0, 0, 0x04, 0x02, 0x00, 0, 0xbb};
    unsigned char res[142];

    req[14] = 0;
    if (fairplay_setup(fp, req, res) != 0) { fputs("FAIL: mode 0 rejected\n", stderr); return 1; }
    req[14] = 3;
    if (fairplay_setup(fp, req, res) != 0) { fputs("FAIL: mode 3 rejected\n", stderr); return 1; }
    /* before the patch this read 142 bytes far past reply_message and sent them to the client */
    req[14] = 200;
    if (fairplay_setup(fp, req, res) == 0) { fputs("FAIL: mode 200 accepted\n", stderr); return 1; }

    fairplay_destroy(fp);
    puts("fairplay_setup_mode: ok");
    return 0;
}
