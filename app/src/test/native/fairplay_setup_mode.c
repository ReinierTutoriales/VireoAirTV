/* UxPlay patches 0044 and 0045: the FairPlay key mode from the network must not index past
 * reply_message[4] (setup) or message_key[4] (handshake, used by playfair_decrypt). */
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

    unsigned char msg[164] = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x03, 0x00, 0, 0, 0, 0x98};
    unsigned char hres[32], ekey[72] = {0}, key[16];
    msg[12] = 3;
    if (fairplay_handshake(fp, msg, hres) != 0) { fputs("FAIL: handshake mode 3 rejected\n", stderr); return 1; }
    if (fairplay_decrypt(fp, ekey, key) != 0) { fputs("FAIL: decrypt after mode 3\n", stderr); return 1; }
    /* before patch 0045 decrypt read message_key[200], far past the table */
    msg[12] = 200;
    if (fairplay_handshake(fp, msg, hres) == 0) { fputs("FAIL: handshake mode 200 accepted\n", stderr); return 1; }
    if (fairplay_decrypt(fp, ekey, key) == 0) { fputs("FAIL: decrypt used a rejected key message\n", stderr); return 1; }

    fairplay_destroy(fp);
    puts("fairplay_setup_mode: ok");
    return 0;
}
