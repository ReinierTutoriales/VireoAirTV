/* UxPlay patch 0051: a master playlist without the remote prefix (relative media URIs) is returned unchanged. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "raop.h"
#include "airplay_video.h"
int main(void) {
    char rel[] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia/1.m3u8\n";
    char *out = adjust_master_playlist(rel, (int) strlen(rel), "https://cdn.example/x", "http://localhost:7000");
    if (!out || strcmp(out, rel)) { fprintf(stderr, "FAIL: relative master changed: [%s]\n", out ? out : "(null)"); return 1; }
    free(out);
    char abs[] = "#EXTM3U\nhttps://cdn.example/x/a.m3u8\nhttps://cdn.example/x/b.m3u8\n";
    out = adjust_master_playlist(abs, (int) strlen(abs), "https://cdn.example/x", "http://localhost:7000");
    if (!out || strcmp(out, "#EXTM3U\nhttp://localhost:7000/a.m3u8\nhttp://localhost:7000/b.m3u8\n")) { fprintf(stderr, "FAIL: absolute master: [%s]\n", out); return 1; }
    free(out);
    puts("hls_master_playlist: ok");
    return 0;
}
