#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
# 前台运行，不注册开机自启。所有运行文件都在本项目忽略的 data/redis 目录。
mkdir -p data/redis
exec /opt/homebrew/opt/redis/bin/redis-server --bind 127.0.0.1 --protected-mode yes --port "${REDIS_PORT:-6379}" --dir "$PWD/data/redis" --appendonly yes --appendfsync everysec --save '' --maxmemory 64mb --maxmemory-policy noeviction
