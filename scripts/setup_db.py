#!/usr/bin/env python3
"""只使用 Python 标准库 + 已安装的 mysql 客户端，不下载 Python 包，不写密码文件。"""
import getpass
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parent.parent
CLIENT = "/opt/homebrew/opt/mysql@8.4/bin/mysql"


def sql_string(value):
    # 本连接显式使用 NO_BACKSLASH_ESCAPES；单引号翻倍即可安全表达字符串字面量。
    return "'" + value.replace("'", "''") + "'"


def main():
    client = CLIENT if Path(CLIENT).is_file() else shutil.which("mysql")
    if not client:
        sys.exit("未找到 mysql 客户端，请先安装 MySQL。")
    if not sys.stdin.isatty():
        sys.exit("请在交互式终端运行，密码不会回显。")
    root_password = getpass.getpass("MySQL root 密码：")
    app_password = getpass.getpass("设置 interview_app 密码（回车则本地学习复用刚输入的密码）：")
    app_password = app_password or root_password
    if not root_password or not app_password:
        sys.exit("密码不能为空。")
    # mysql 子进程从环境中读取管理员密码，从 stdin 读取 SQL；两者均不写入文件。
    env = os.environ.copy()
    env["MYSQL_PWD"] = root_password
    args = [client, "--protocol=TCP", "--host=127.0.0.1", "--port=3306", "--user=root",
            "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
    # 已有账号时不重置密码，防止重复执行脚本意外改动已有配置。
    account_sql = (
        "SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';\n"
        "CREATE USER IF NOT EXISTS 'interview_app'@'localhost' IDENTIFIED BY "
        + sql_string(app_password) + ";\n"
        "GRANT SELECT, INSERT, UPDATE, DELETE ON interview_agent.* TO 'interview_app'@'localhost';\n"
        "SELECT '初始化完成：interview_agent / interview_app';\n"
    )
    schema = (PROJECT / "scripts/mysql/schema.sql").read_text()
    schema += "\n" + (PROJECT / "scripts/mysql/002-interviews.sql").read_text()
    schema += "\n" + (PROJECT / "scripts/mysql/003-ai-feedback.sql").read_text()
    schema += "\n" + (PROJECT / "scripts/mysql/004-knowledge.sql").read_text()
    result = subprocess.run(args, input=schema + "\n" + account_sql,
                            text=True, env=env, capture_output=True)
    if result.returncode:
        # mysql 的错误可能含原始 SQL；不打印可能含密码的错误细节。
        sys.exit("初始化失败。请检查 MySQL 是否启动、root 密码及新密码是否满足密码策略。已有数据未主动删除。")
    # 验证提供的应用密码；若账号已经存在且密码不同，明确提醒，不自动重置。
    env["MYSQL_PWD"] = app_password
    app_args = [arg if arg != "--user=root" else "--user=interview_app" for arg in args]
    check = subprocess.run(app_args, input="SELECT COUNT(*) FROM interview_agent.questions;",
                           text=True, env=env, capture_output=True)
    if check.returncode:
        sys.exit("表已初始化，但应用账号连接失败。已有账号不会被重置，请使用其原密码。")
    print("初始化成功：interview_agent 数据库；interview_app 本机账号；题目数：" + check.stdout.strip())
    print("密码未写入项目文件。MySQL 会按认证机制保存账号的认证信息。")


if __name__ == "__main__":
    main()
