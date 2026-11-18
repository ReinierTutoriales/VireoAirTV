#!/usr/bin/env bash
# libFuzzer + ASan/UBSan on the native code that parses sender data (https://llvm.org/docs/LibFuzzer.html).
# Needs clang with the compiler-rt fuzzer runtime (Debian/Ubuntu: libclang-rt-<ver>-dev) and libssl-dev.
# Usage: scripts/fuzz.sh [seconds per target]   (run after a build, so the UxPlay patches are applied)
set -euo pipefail
secs=${1:-60}
cpp=app/src/main/cpp
ux=$cpp/third_party/UxPlay/lib
pl=$cpp/third_party/libplist
src=app/src/test/fuzz
out=${FUZZ_OUT:-build/fuzz}
mkdir -p "$out"
touch "$out/config.h"
flags=(-g -O1 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=undefined)
plist=(-D_GNU_SOURCE -DHAVE_STRNDUP -DPACKAGE_VERSION='"2.8.1"' -I"$out" -I"$pl/include" -I"$pl/src" -I"$pl/libcnary/include"
       "$pl"/src/*.c "$pl"/libcnary/node.c "$pl"/libcnary/node_list.c -lm)

build() { echo "== $1"; clang "${flags[@]}" "${@:2}" -o "$out/$1"; }
build fuzz_hls -I"$ux" -I"$pl/include" $src/fuzz_hls.c "$ux/airplay_video.c" "$ux/logger.c" -lpthread
build fuzz_http_request -I"$ux" -I"$ux/llhttp" $src/fuzz_http_request.c "$ux/http_request.c" "$ux"/llhttp/*.c
build fuzz_fairplay -I"$ux" -I"$ux/playfair" $src/fuzz_fairplay.c "$ux/fairplay_playfair.c" "$ux/fairplay_v2/fairplay_v2.c" \
      "$ux"/playfair/*.c "$ux/logger.c" -lcrypto -lpthread -lm
build fuzz_audio_packet -I"$ux" $src/fuzz_audio_packet.c "$ux/raop_buffer.c" "$ux/crypto.c" "$ux/logger.c" "$ux/byteutils.c" \
      "$ux/utils.c" -lcrypto -lpthread
build fuzz_bplist "${plist[@]}" $src/fuzz_bplist.c

for t in fuzz_hls fuzz_http_request fuzz_fairplay fuzz_audio_packet fuzz_bplist; do
    mkdir -p "$out/corpus/$t"
    echo "== run $t (${secs}s)"
    "$out/$t" "$out/corpus/$t" -max_total_time="$secs" -print_final_stats=1 -artifact_prefix="$out/$t-" 2>&1 | tail -3
done
