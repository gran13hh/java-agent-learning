#!/bin/bash
set -euo pipefail
# Finder 双击时 PATH 比交互终端短，显式补充已安装的 Homebrew 工具。
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export HOMEBREW_NO_AUTO_UPDATE=1
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
app_port="${SERVER_PORT:-8080}"
redis_port="${REDIS_PORT:-6379}"
url="http://127.0.0.1:$app_port"
rebuild=false
open_browser=true
for option in "$@"; do
  case "$option" in
    --build) rebuild=true ;;
    --no-open) open_browser=false ;;
    *) echo "用法：./scripts/start.sh [--build] [--no-open]" >&2; exit 1 ;;
  esac
done
fail() { echo "启动失败：$*" >&2; exit 1; }
for port in "$app_port" "$redis_port"; do
  [[ "$port" =~ ^[0-9]+$ ]] && ((port > 0 && port < 65536)) || fail "端口无效：$port"
done
[[ "$app_port" != "$redis_port" && "$app_port" != 3306 && "$redis_port" != 3306 ]] || fail 'Java、Redis 与 MySQL 必须使用不同端口。'
[[ "${REDIS_HOST:-127.0.0.1}" == 127.0.0.1 || "${REDIS_HOST:-127.0.0.1}" == localhost ]] || fail '一键入口只管理本机 Redis。'
mkdir -p logs data/launcher
# 防止双击两次时并发启动。正常退出、失败、Ctrl+C 都移除锁目录。
lock_dir="$project_dir/data/launcher/start.lock"
mkdir "$lock_dir" 2>/dev/null || fail "另一个启动流程正在运行。如果上次被强制终止，确认没有启动流程后删除 $lock_dir 再试。"
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM
mysql_admin=/opt/homebrew/opt/mysql@8.4/bin/mysqladmin
redis_cli=/opt/homebrew/opt/redis/bin/redis-cli
[[ -x "$mysql_admin" ]] || fail '未找到已安装的 MySQL 8.4。脚本不会自动下载软件。'
[[ -x "$redis_cli" ]] || fail '未找到已安装的 Redis。脚本不会自动下载软件。'
[[ -f config/application-local.yml || -n "${DB_PASSWORD:-}" ]] || fail '请先填写 config/application-local.yml。'
if [[ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || fail '找不到 Java，请配置 JDK 21。'
command -v python3 >/dev/null || fail '找不到已安装的 Python 3，后台启动需要其标准库。'
listeners() { /usr/sbin/lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | sort -u || true; }
mysql_ready() {
  # mysqladmin ping 在服务返回 Access denied 时也视为服务存活；账号认证由应用最终验证。
  "$mysql_admin" --protocol=TCP --host=127.0.0.1 --port=3306 --connect-timeout=1 ping >/dev/null 2>&1
}
redis_ready() { [[ "$("$redis_cli" -t 2 -h 127.0.0.1 -p "$redis_port" PING 2>/dev/null)" == PONG ]]; }
app_ready() { curl -fsS --connect-timeout 1 --max-time 3 "$url/api/questions?page=1&size=1" >/dev/null 2>&1; }
wait_ready() {
  local check="$1" attempts="$2"
  for ((i=0; i<attempts; i++)); do
    if "$check"; then return 0; fi
    sleep 1
  done
  return 1
}
detach() {
  # 独立会话 + 关闭 stdin，终端关闭时不会随终端进程组退出；只用 Python 标准库。
  python3 - "$1" "$2" <<'PYTHON'
import subprocess, sys
with open(sys.argv[1], 'ab', buffering=0) as log:
    process = subprocess.Popen([sys.argv[2]], stdin=subprocess.DEVNULL,
                               stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
print(process.pid)
PYTHON
}
save_process() {
  local name="$1" pid="$2" birth
  birth="$(ps -p "$pid" -o lstart=)"
  [[ -n "$birth" ]] || fail "$name 进程已退出，请查看 logs/。"
  # PID + 启动时间，停止时再次核对，避免 PID 被其他进程复用后误杀。
  printf '%s\n%s\n' "$pid" "$birth" > "data/launcher/$name.pid"
}

# 先识别占用 Java 端口的进程，绝不因为端口冲突主动杀进程。
app_pid="$(listeners "$app_port")"
if [[ -n "$app_pid" ]]; then
  [[ "$app_pid" != *$'\n'* ]] || fail "$app_port 有多个监听进程，请检查端口。"
  command_line="$(ps -p "$app_pid" -o command=)"
  process_dir="$(/usr/sbin/lsof -a -p "$app_pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  [[ "$command_line" == *interview-agent-0.1.0.jar* && "$process_dir" == "$project_dir" ]] || fail "$app_port 已被其他程序或 IDEA 运行实例占用；请直接访问已有应用，或先在其终端停止。"
  [[ "$rebuild" == false ]] || fail '重新打包前请先停止现有 Java 应用；不会覆盖运行中的 JAR。'
fi
if [[ -z "$app_pid" && -f data/launcher/app.pid ]]; then
  old_pid="$(head -n 1 data/launcher/app.pid)"
  old_birth="$(sed -n '2p' data/launcher/app.pid)"
  if [[ "$old_pid" =~ ^[0-9]+$ && -n "$old_birth" && "$(ps -p "$old_pid" -o lstart= 2>/dev/null || true)" == "$old_birth" ]]; then
    fail '本脚本管理的 Java 实例仍在运行（可能使用其他端口），请先用停止入口关闭它。'
  fi
fi
if [[ -z "$app_pid" && ( "$rebuild" == true || ! -f target/interview-agent-0.1.0.jar ) ]]; then
  [[ -z "$(/usr/sbin/lsof -t "$project_dir/target/interview-agent-0.1.0.jar" 2>/dev/null || true)" ]] || fail 'JAR 正被进程使用，请先停止相关应用后再打包。'
  echo '正在离线打包（不会下载依赖），详情见 logs/build.log …'
  ./scripts/mvn.sh -o -B -ntp package >> logs/build.log 2>&1 || fail '离线打包失败，请查看 logs/build.log。'
fi

echo '检查 MySQL …'
if ! mysql_ready; then
  [[ -z "$(listeners 3306)" ]] || fail '3306 已被占用，但 MySQL 未就绪。'
  brew services run mysql@8.4 >> logs/mysql-start.log 2>&1 || fail 'MySQL 启动失败，请查看 logs/mysql-start.log。'
  wait_ready mysql_ready 30 || fail 'MySQL 未在 30 秒内就绪，请查看 logs/mysql-start.log。'
fi
echo 'MySQL 已就绪。'

echo '检查 Redis …'
if ! redis_ready; then
  [[ -z "$(listeners "$redis_port")" ]] || fail "$redis_port 已被占用，但 Redis PING 未通过。"
  # 一个项目只使用一个数据目录，避免切换端口时让两个 Redis 同时写同一份 AOF。
  if [[ -f data/launcher/redis.pid ]]; then
    old_pid="$(head -n 1 data/launcher/redis.pid)"
    old_birth="$(sed -n '2p' data/launcher/redis.pid)"
    if [[ "$old_pid" =~ ^[0-9]+$ && "$(ps -p "$old_pid" -o lstart= 2>/dev/null || true)" == "$old_birth" ]]; then
      fail '本脚本已启动另一个端口的 Redis，请先用停止入口关闭它。'
    fi
  fi
  [[ -z "$(/usr/sbin/lsof -t +D "$project_dir/data/redis" 2>/dev/null || true)" ]] || fail 'Redis 数据目录正在被进程使用，请检查已有 Redis。'
  redis_pid="$(detach logs/redis.log ./scripts/run-redis.sh)"
  save_process redis "$redis_pid"
  wait_ready redis_ready 20 || fail 'Redis 未就绪，请查看 logs/redis.log。'
fi
echo 'Redis 已就绪。'

if [[ -z "$app_pid" ]]; then
  echo '启动 Java 应用 …'
  app_pid="$(detach logs/application.log ./scripts/run.sh)"
  save_process app "$app_pid"
else
  echo "复用已运行的 Java 应用（PID ${app_pid}）。"
fi
wait_ready app_ready 45 || fail '应用未就绪，请查看 logs/application.log 或已有应用的终端日志。'
echo "启动成功：$url/"
echo '运行日志：项目 logs/ 目录。终端窗口可以关闭，服务继续在后台运行。'
echo '默认使用现有 JAR；修改源码后先停止应用，再执行 ./scripts/start.sh --build。'
if [[ "$open_browser" == true ]]; then
  open "$url/" || echo "未能自动打开浏览器，请手动访问 $url/。"
fi
