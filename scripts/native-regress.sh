#!/usr/bin/env bash
# Host regression tests for patched native code. Run from the repo root after
# `./gradlew :app:applyUxplayPatches` (or any native build) has applied the patches.
set -euo pipefail
cpp=app/src/main/cpp
ux=$cpp/third_party/UxPlay/lib
pl=$cpp/third_party/libplist
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
touch "$out/config.h"
cc=${CC:-cc}
flags=(-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all)

"$cc" "${flags[@]}" -I"$ux" app/src/test/native/http_request_limits.c \
    "$ux/http_request.c" "$ux"/llhttp/*.c -o "$out/http_request_limits"
"$out/http_request_limits"

"$cc" "${flags[@]}" -I"$ux" -I"$ux/llhttp" -I"$pl/include" app/src/test/native/hls_master_playlist.c \
    "$ux/airplay_video.c" "$ux/logger.c" -lpthread -o "$out/hls_master_playlist"
"$out/hls_master_playlist"

"$cc" "${flags[@]}" -I"$ux" -I"$ux/playfair" app/src/test/native/fairplay_setup_mode.c \
    "$ux/fairplay_playfair.c" "$ux/fairplay_v2/fairplay_v2.c" "$ux"/playfair/*.c "$ux/logger.c" \
    -lcrypto -lpthread -lm -o "$out/fairplay_setup_mode"
"$out/fairplay_setup_mode"

"$cc" "${flags[@]}" -D_GNU_SOURCE -DHAVE_STRNDUP -DPACKAGE_VERSION='"2.8.1"' \
    -I"$out" -I"$pl/include" -I"$pl/src" -I"$pl/libcnary/include" \
    app/src/test/native/bplist_expansion.c "$pl"/src/*.c \
    "$pl/libcnary/node.c" "$pl/libcnary/node_list.c" -lm -o "$out/bplist_expansion"
ASAN_OPTIONS=allocator_may_return_null=1 "$out/bplist_expansion"
