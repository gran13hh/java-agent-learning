#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export RUN_REDIS_TESTS=true
exec ./scripts/test-mysql.sh "$@"
