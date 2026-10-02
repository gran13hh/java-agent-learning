#!/bin/bash
set -euo pipefail
# 本脚本不下载 Maven 分发包：优先复用指定版本，再找 IDEA 内置 Maven/已有全局 Maven。
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ -n "${MAVEN_HOME:-}" && -x "$MAVEN_HOME/bin/mvn" ]]; then
  maven_cmd="$MAVEN_HOME/bin/mvn"
elif [[ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]]; then
  maven_cmd="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then
  maven_cmd="$(command -v mvn)"
else
  echo '未找到 Maven。请在 IDEA 中使用 Bundled Maven，或设置 MAVEN_HOME。' >&2
  exit 1
fi
exec "$maven_cmd" "$@"
