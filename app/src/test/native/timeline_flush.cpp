// RTSP FLUSH: audio buffered before a pause or seek must not play after it.
#include <cstdio>
#include <vector>
#include "timeline_buffer.h"

int main() {
    TimelineBuffer tl(44100, 2, 100, 95);
    std::vector<int16_t> pcm(44100 * 2 / 2, 1000);  // 0.5 s of non-silent stereo audio
    tl.write(pcm.data(), pcm.size(), 0);             // pts 0: written straight into the ring
    std::vector<int16_t> out(256 * 2);
    tl.read(out.data(), 256);
    bool sound = false;
    for (int16_t s : out) sound |= s != 0;
    if (!sound) { fputs("FAIL: buffered audio not played before the flush\n", stderr); return 1; }

    tl.requestFlush();
    for (int i = 0; i < 40; i++) {                   // more reads than the old tail would last
        tl.read(out.data(), 256);
        for (int16_t s : out) if (s != 0) { fputs("FAIL: stale audio played after FLUSH\n", stderr); return 1; }
    }
    puts("timeline_flush: ok");
    return 0;
}
