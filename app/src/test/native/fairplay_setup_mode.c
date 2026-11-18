/* UxPlay patches 0044 and 0045: the FairPlay key mode from the network must not index past
 * reply_message[4] (setup) or message_key[4] (handshake, used by playfair_decrypt).
 * Patch 0046 (FairPlay v2): v2 records are validated and v3 keeps working after a v2 setup. */
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

    /* FairPlay v2 (selector 2, macOS Music) */
    unsigned char v2[16] = {0x46, 0x50, 0x4c, 0x59, 0x02, 0x01, 0x01, 0x00, 0, 0, 0, 0x04, 0x02, 0x00, 1, 0xbb};
    memset(res, 0, sizeof(res));
    if (fairplay_setup(fp, v2, res) != 0) { fputs("FAIL: v2 setup rejected\n", stderr); return 1; }
    if (memcmp(res, "FPLY\x02\x01\x02\x00\x00\x00\x00\x82\x02\x01", 14) != 0) { fputs("FAIL: v2 reply header\n", stderr); return 1; }
    /* the v2 handshake must carry the sender's confirmation; zeros are refused */
    unsigned char v2hs[164] = {0x46, 0x50, 0x4c, 0x59, 0x02, 0x01, 0x03, 0x00, 0, 0, 0, 0x98, 1, 0x8f, 0x1a, 0x9c};
    if (fairplay_handshake(fp, v2hs, hres) == 0) { fputs("FAIL: forged v2 handshake accepted\n", stderr); return 1; }
    if (fairplay_decrypt(fp, ekey, key) == 0) { fputs("FAIL: decrypt after a failed v2 handshake\n", stderr); return 1; }
    v2[14] = 4;
    if (fairplay_setup(fp, v2, res) == 0) { fputs("FAIL: v2 mode 4 accepted\n", stderr); return 1; }
    v2[14] = 1; v2[15] = 0;
    if (fairplay_setup(fp, v2, res) == 0) { fputs("FAIL: v2 bad trailer accepted\n", stderr); return 1; }
    /* an iPhone (v3) after a Mac (v2) on the same connection */
    msg[12] = 3;
    if (fairplay_handshake(fp, msg, hres) != 0 || fairplay_decrypt(fp, ekey, key) != 0) { fputs("FAIL: v3 after v2\n", stderr); return 1; }

    fairplay_destroy(fp);
    puts("fairplay_setup_mode: ok");
    return 0;
}
