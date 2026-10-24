#!/bin/bash
set -euo pipefail
# 本脚本不下载 Maven 分发包：优先使用本地安装，最后回退到 IDEA 内置 Maven。
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ -n "${MAVEN_HOME:-}" && -x "$MAVEN_HOME/bin/mvn" ]]; then
  maven_cmd="$MAVEN_HOME/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then
  maven_cmd="$(command -v mvn)"
else
  # Finder 等环境可能没有加载 .zshrc，补充查找用户工具目录中的独立安装。
  maven_cmd=""
  for candidate in "$HOME"/Library/Developer/Tools/apache-maven-*/bin/mvn; do
    if [[ -x "$candidate" ]]; then
      maven_cmd="$candidate"
      break
    fi
  done
  if [[ -z "$maven_cmd" && -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]]; then
    maven_cmd="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
  fi
fi
if [[ -z "$maven_cmd" ]]; then
  echo '未找到 Maven。请设置 MAVEN_HOME、将 mvn 加入 PATH，或使用 IDEA 内置 Maven。' >&2
  exit 1
fi
exec "$maven_cmd" "$@"
