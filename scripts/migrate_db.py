#!/usr/bin/env python3
"""给已有数据库添加 V1/V2/V3/V4 表，只复用本机 mysql 客户端与 Python 标准库。"""
import getpass
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    project = Path(__file__).resolve().parent.parent
    client = "/opt/homebrew/opt/mysql@8.4/bin/mysql"
    client = client if Path(client).is_file() else shutil.which("mysql")
    if not client:
        sys.exit("未找到 mysql 客户端。")
    if not sys.stdin.isatty():
        sys.exit("请在交互式终端运行，密码不会回显。")
    env = os.environ.copy()
    env["MYSQL_PWD"] = getpass.getpass("MySQL root 密码（仅用于增量建表）：")
    sql = (project / "scripts/mysql/002-interviews.sql").read_text()
    sql += "\n" + (project / "scripts/mysql/003-ai-feedback.sql").read_text()
    sql += "\n" + (project / "scripts/mysql/004-knowledge.sql").read_text()
    sql += "\n" + (project / "scripts/mysql/005-agent.sql").read_text()
    result = subprocess.run([client, "--protocol=TCP", "--host=127.0.0.1", "--port=3306",
                             "--user=root", "--default-character-set=utf8mb4"],
                            input=sql, text=True, env=env, capture_output=True)
    if result.returncode:
        sys.exit("迁移失败，请检查 MySQL、root 密码及 interview_agent 数据库。可修复后重跑，不会删除已有数据。")
    print("V1/V2/V3/V4 增量建表完成：面试、AI 反馈任务、请求限流、知识库与 Agent 记录表；已有数据保留。")


if __name__ == "__main__":
    main()
