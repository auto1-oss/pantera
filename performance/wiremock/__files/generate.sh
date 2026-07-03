#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
head -c 102400   /dev/urandom > body-100k.bin
head -c 1048576  /dev/urandom > body-1m.bin
head -c 10485760 /dev/urandom > body-10m.bin
echo "Generated:"
ls -lh body-*.bin
