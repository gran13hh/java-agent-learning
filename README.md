# InterviewAgent

一个基于 Java 和 Spring AI 的本地智能面试教练，提供题库、模拟面试、知识库检索和受控 Agent 复习助手。采用单体后端与原生网页，默认监听 `127.0.0.1:8080`。

## 功能

- **题库**：新增、查询、主题筛选和分页；支持 Java、MySQL、Redis、Agent 主题。
- **模拟面试**：创建会话、提交回答、保存历史；支持 Mock 或真实模型反馈，以及单个追问建议。
- **知识库 / RAG**：导入文本或 Markdown、分块、向量索引、相似度检索；反馈保存引用原文快照，证据不足时明确说明。
- **复习助手**：模型可请求查询题库、检索资料和读取选定面试；应用控制执行预算，保存每一步工具调用，网页通过 SSE 展示进度。
- **工程支持**：Redis 题库缓存、Redis + MySQL 请求限流、执行幂等、自动化测试和固定评测用例。

当前定位为本地单用户应用，尚未实现多人账号与鉴权、交互式多轮追问、语音面试或独立薄弱项统计。

## 技术栈

| 组件 | 用途 |
| --- | --- |
| Java 21 / Maven 3 | 编译、依赖管理、测试与打包 |
| Spring Boot 4.0.8 / Spring MVC / Validation | HTTP API、参数校验和应用配置 |
| Spring JDBC / MySQL 8.4 | 业务记录、向量、执行日志和持久化请求预算 |
| Spring AI 2.0.1 | OpenAI 兼容聊天与 Embedding 接口、工具调用适配 |
| Redis / Spring Data Redis | 题库缓存、滑动窗口限流 |
| HTML / CSS / JavaScript | 题库、面试、知识库与 Agent 网页 |
| JUnit / Spring Test | 业务、HTTP、数据库和 Redis 测试 |

无需 Node.js、Docker、本地模型权重或独立向量数据库。具体依赖版本以 [pom.xml](pom.xml) 为准。

## 环境准备与配置

准备 JDK 21、Maven 3、MySQL 8.4、Redis，以及运行辅助脚本需要的 Python 3 和 MySQL 客户端。Python 脚本仅使用标准库。`scripts/mvn.sh` 优先使用 `MAVEN_HOME`，其次使用标准安装位置的 IDEA 内置 Maven，最后使用 PATH 中的 Maven。

macOS 的一键启动与 Redis 脚本按 Apple Silicon Homebrew 的 `/opt/homebrew` 路径编写；其他环境可自行启动 MySQL / Redis，再运行 Java 应用。IDEA 的 Project SDK 和 Maven JDK 均设置为 21，运行工作目录设为项目根目录。

在项目根目录执行以下步骤：

1. 启动 MySQL。全新数据库运行 `python3 scripts/setup_db.py`，按提示输入管理员密码并设置 `interview_app` 应用账号密码。脚本创建数据库、当前全部表和初始题目；已有账号不会被重置。已有旧版数据库用 `python3 scripts/migrate_db.py` 增量建表。
2. 将 [config/application-local.example.yml](config/application-local.example.yml) 复制为同目录的 `application-local.yml`，填写应用账号密码、聊天和向量模型的 Base URL、API Key、模型名及向量维度。已有本地配置无需覆盖。Base URL 包含服务商的版本前缀，例如 `/v1`。
3. 启动 Redis。macOS 可在独立终端运行 `./scripts/run-redis.sh`，或交由下面的一键脚本启动。

应用自动加载本地 YAML；文件不纳入 Git，也不打入 JAR。模板中的 `DB_PASSWORD`、`CHAT_API_KEY`、`EMBEDDING_API_KEY` 环境变量可覆盖文件中的默认值。数据库连接另支持 `DB_URL`、`DB_USERNAME`；端口支持 `SERVER_PORT`、`REDIS_PORT`。

无模型配置时，可设置 `AI_FEEDBACK_MODE=MOCK` 体验题库和模拟面试流程。Redis 不可用时题库查询回退 MySQL；真实模型请求会被拒绝，避免绕过限流。

## 构建与运行

```bash
# 首次构建可能下载 pom.xml 所需依赖，默认缓存到 ~/.m2/repository。
./scripts/mvn.sh -B -ntp package

# 已缓存全部依赖后可离线构建。
./scripts/mvn.sh -o -B -ntp package

# MySQL、Redis 已启动时运行应用。
./scripts/run.sh
```

构建产物为 `target/interview-agent-0.1.0.jar`。只重启应用不需要重新打包；修改 Java 或静态页面后需要重新构建。也可在项目根目录执行 `java -jar target/interview-agent-0.1.0.jar`。

macOS 可双击根目录的 `启动项目.command` 和 `停止项目.command`，或使用：

```bash
./scripts/start.sh            # 复用现有 JAR；缺少 JAR 时尝试离线构建
./scripts/stop.sh             # 停止此脚本启动的 Java / Redis
./scripts/start.sh --build    # 修改代码后，先停止应用，再离线重新构建并启动
```

启动脚本不安装软件或联网下载依赖；停止脚本保留 MySQL 和原先已运行的服务。启动日志位于 `logs/`，进程记录位于 `data/launcher/`。

| 页面 | 地址 |
| --- | --- |
| 题库 | http://127.0.0.1:8080/ |
| 模拟面试 | http://127.0.0.1:8080/interview.html |
| 知识库 | http://127.0.0.1:8080/knowledge.html |
| 复习助手 | http://127.0.0.1:8080/agent.html |

## 架构与接口

- [架构与设计边界](docs/ARCHITECTURE.md)：数据流、RAG、Agent、缓存、限流和 SSE。
- [HTTP 接口](docs/API.md)：接口清单、请求示例及调用注意事项。
- [测试与评测](docs/VALIDATION.md)：测试命令、固定语料与真实模型评测步骤。

源码按 `controller`、`service`、`repository`、`domain`、`dto`、`ai`、`knowledge`、`agent`、`cache` 和 `exception` 分层。网页位于 `src/main/resources/static/`，数据库结构位于 `scripts/mysql/`。

## 本地数据

业务数据与向量持久化在 MySQL；项目脚本启动的 Redis 数据保存在 `data/redis/`；评测输出在 `data/evaluation/`。`target/` 是构建产物，清理它不会删除数据库数据。

仓库保留源码、脚本、配置模板、项目说明、示例语料和评测用例。本地配置、个人学习笔记、下载记录、运行报告、日志、依赖缓存和构建产物不纳入版本管理。
