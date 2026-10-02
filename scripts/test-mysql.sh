#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ -z "${DB_PASSWORD:-}" ]]; then
  read -r -s -p "MySQL 应用账号 ${DB_USERNAME:-interview_app} 的密码：" DB_PASSWORD
  echo
fi
if [[ -z "$DB_PASSWORD" ]]; then
  echo '密码不能为空。' >&2
  exit 1
fi
export DB_PASSWORD
export RUN_MYSQL_TESTS=true
exec ./scripts/mvn.sh -B -ntp "$@" test
