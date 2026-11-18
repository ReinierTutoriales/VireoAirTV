/* libFuzzer target: HLS playlist text from the sender (FCUP responses, playlists served to the player). */
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "raop.h"
#include "airplay_video.h"

static const char *PREFIX = "https://a.b/c";
static const char *LOCAL = "http://localhost:7000";

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 1 || size > 1 << 16) return 0;
    int mode = data[0] % 5;
    char *text = malloc(size);
    memcpy(text, data + 1, size - 1);
    text[size - 1] = '\0';
    int len = (int) strlen(text);
    switch (mode) {
    case 0: free(adjust_master_playlist(text, len, PREFIX, (char *) LOCAL)); break;
    case 1: free(adjust_yt_condensed_playlist(text)); break;
    case 2: { float d; bool e; analyze_media_playlist(text, &d, &e); break; }
    case 3: {
        char **table = NULL; int n = 0;
        if (create_media_uri_table(PREFIX, text, len, &table, &n) == 0 && table) {
            for (int i = 0; i < n; i++) free(table[i]);
            free(table);
        }
        break;
    }
    case 4: {
        airplay_video_t *av = airplay_video_init((raop_t *) (uintptr_t) 1, 7000, "en");
        char *copy = strdup(text);
        char *out = select_master_playlist_language(av, copy);  /* frees copy when it returns a new one */
        free(out);
        airplay_video_destroy(av);
        break;
    }
    }
    free(text);
    return 0;
}
