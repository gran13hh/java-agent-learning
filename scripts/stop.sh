#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/launcher
lock_dir="$PWD/data/launcher/start.lock"
mkdir "$lock_dir" 2>/dev/null || { echo '另一个启动或停止流程正在运行，请稍后再试。' >&2; exit 1; }
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM
# 只关闭启动脚本记录的进程，且必须同时满足 PID、出生时间和命令类型。
for name in app redis; do
  file="data/launcher/$name.pid"
  if [[ ! -f "$file" ]]; then echo "没有由一键入口管理的 $name 进程，跳过。"; continue; fi
  pid="$(head -n 1 "$file")"
  birth="$(sed -n '2p' "$file")"
  if [[ ! "$pid" =~ ^[0-9]+$ || -z "$birth" ]]; then echo "$file 内容异常，请人工检查。" >&2; exit 1; fi
  current_birth="$(ps -p "$pid" -o lstart= 2>/dev/null || true)"
  if [[ "$current_birth" != "$birth" ]]; then rm "$file"; echo "$name 已退出，清理旧 PID 记录。"; continue; fi
  command_line="$(ps -p "$pid" -o command=)"
  if [[ "$name" == app && "$command_line" != *interview-agent-0.1.0.jar* ]] || [[ "$name" == redis && "$command_line" != *redis-server* ]]; then
    echo "$name 的进程类型不符，未发送停止信号。" >&2; exit 1
  fi
  kill -TERM "$pid"
  stopped=false
  for ((i=0; i<45; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then stopped=true; break; fi
    sleep 1
  done
  if [[ "$stopped" != true ]]; then
    echo "$name 仍在优雅停止中。保留记录，稍后重试；不会强杀，也不会继续停止 Redis。" >&2
    exit 1
  fi
  rm "$file"
  echo "$name 已停止。"
done
echo 'MySQL 和你手动启动的服务保持原状；需要时可自行执行 brew services stop mysql@8.4。'
