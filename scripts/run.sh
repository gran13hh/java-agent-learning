#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
jar_file="target/interview-agent-0.1.0.jar"
if [[ ! -f "$jar_file" ]]; then
  echo '请先运行 ./scripts/mvn.sh -B -ntp package 生成可执行 JAR。' >&2
  exit 1
fi
if [[ -z "${DB_PASSWORD:-}" && ! -f config/application-local.yml ]]; then
  read -r -s -p "MySQL 应用账号 ${DB_USERNAME:-interview_app} 的密码：" DB_PASSWORD
  echo
  if [[ -z "$DB_PASSWORD" ]]; then
    echo '密码不能为空。' >&2
    exit 1
  fi
fi
# 本地 YAML 存在时交给 Spring 读取；环境变量仍可覆盖 YAML 中的默认密码。
if [[ -n "${DB_PASSWORD:-}" ]]; then
  export DB_PASSWORD
fi
exec java -jar "$jar_file"
