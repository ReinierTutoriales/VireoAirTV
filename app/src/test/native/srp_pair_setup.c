/* UxPlay patches 0057-0059: pair-setup-pin requests that crashed the receiver before pairing, the verifier
 * leak, and a step 3 accepted without the PIN proof of step 2. */
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include "pairing.h"
#include "crypto.h"

/* step 3 encrypted under the key derived from the all-zero session key left by step 1 */
static int forged_step3(pairing_session_t *s, pairing_t *p) {
    unsigned char zero[SRP_SESSION_KEY_SIZE] = {0}, hash[64], key[16], iv[16];
    unsigned char pk[ED25519_KEY_SIZE] = {7}, epk[ED25519_KEY_SIZE], tag[GCM_AUTHTAG_SIZE];
    const char *k = "Pair-Setup-AES-Key", *v = "Pair-Setup-AES-IV";
    sha_ctx_t *c = sha_init(); sha_update(c, (const uint8_t *) k, strlen(k)); sha_update(c, zero, sizeof(zero)); sha_final(c, hash, NULL); sha_destroy(c);
    memcpy(key, hash, 16);
    c = sha_init(); sha_update(c, (const uint8_t *) v, strlen(v)); sha_update(c, zero, sizeof(zero)); sha_final(c, hash, NULL); sha_destroy(c);
    memcpy(iv, hash, 16); iv[15]++;
    gcm_encrypt(pk, ED25519_KEY_SIZE, epk, key, iv, tag);
    return srp_confirm_pair_setup(s, p, epk, tag);
}

int main(void) {
    int res = 0;
    pairing_t *pairing = pairing_init_generate("00:11:22:33:44:55", "", &res);
    if (!pairing) { fputs("FAIL: pairing init\n", stderr); return 1; }
    unsigned char A[384] = {0};
    unsigned char proof[20] = {0};

    /* proof step without the first step: no SRP state on the session */
    pairing_session_t *s = pairing_session_init(pairing);
    if (srp_validate_proof(s, pairing, A, sizeof(A), proof, sizeof(proof), sizeof(proof)) == 0) { fputs("FAIL: proof accepted without setup\n", stderr); return 1; }
    pairing_session_destroy(s);

    /* SRP-6a: A = 0 (mod N) must be refused, not handed to the verifier as NULL */
    s = pairing_session_init(pairing);
    const char *salt = NULL; char *pk = NULL; int len_salt = 0, len_pk = 0;
    if (srp_new_user(s, pairing, "AA:BB:CC:DD:EE:FF", "1234", &salt, &len_salt, &pk, &len_pk) < 0) { fputs("FAIL: srp_new_user\n", stderr); return 1; }
    free(pk);  /* the handler frees it the same way */
    if (srp_validate_proof(s, pairing, A, sizeof(A), proof, sizeof(proof), sizeof(proof)) == 0) { fputs("FAIL: A = 0 accepted\n", stderr); return 1; }
    pairing_session_destroy(s);

    /* step 1, then step 3 without the PIN proof of step 2 */
    s = pairing_session_init(pairing);
    if (srp_new_user(s, pairing, "AA:BB:CC:DD:EE:FF", "1234", &salt, &len_salt, &pk, &len_pk) < 0) { fputs("FAIL: srp_new_user\n", stderr); return 1; }
    free(pk);
    if (forged_step3(s, pairing) > 0) { fputs("FAIL: step 3 accepted without the PIN\n", stderr); return 1; }
    /* and step 3 alone, with no SRP state at all */
    if (forged_step3(s, pairing) > 0) { fputs("FAIL: step 3 accepted without setup\n", stderr); return 1; }
    pairing_session_destroy(s);

    pairing_destroy(pairing);
    puts("srp_pair_setup: ok");
    return 0;
}
