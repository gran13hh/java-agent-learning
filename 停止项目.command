#!/bin/bash
cd "$(dirname "$0")" || exit 1
./scripts/stop.sh
result=$?
if [[ -t 0 ]]; then read -r -p '按回车关闭此窗口…' _; fi
exit "$result"
