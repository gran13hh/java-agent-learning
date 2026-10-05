# HTTP 接口

默认 Base URL 为 `http://127.0.0.1:8080`，请求与响应使用 JSON（SSE 除外）。主题枚举为 `JAVA`、`MYSQL`、`REDIS`、`AGENT`；难度枚举为 `EASY`、`MEDIUM`、`HARD`。分页从 1 开始。

## 接口清单

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET / POST | `/api/questions` | 分页查询 / 新增题目；查询支持 `topic`、`page`、`size` |
| GET | `/api/questions/{id}` | 题目详情 |
| GET / POST | `/api/interviews` | 分页查询 / 创建面试 |
| GET | `/api/interviews/{id}` | 面试详情及轮次 |
| POST | `/api/interviews/{id}/answers` | 提交指定轮次的回答 |
| POST | `/api/interviews/{sessionId}/turns/{turnId}/feedback` | 请求生成或重试反馈 |
| GET | `/api/ai/status` | 反馈模式与配置的请求额度 |
| POST | `/api/ai/embeddings/check` | 实际调用向量模型进行连通性检查，消耗请求额度 |
| GET / POST | `/api/knowledge/documents` | 列出 / 导入资料 |
| GET | `/api/knowledge/documents/{id}` | 文档详情、片段与索引状态 |
| POST | `/api/knowledge/documents/{id}/index` | 建立或更新索引，可能调用向量 API |
| POST | `/api/knowledge/search` | 向量检索，调用向量 API |
| GET / POST | `/api/agent/runs` | 列出 / 创建 Agent 运行 |
| GET | `/api/agent/runs/{id}` | 运行详情与执行步骤 |
| POST | `/api/agent/runs/{id}/execute` | 执行 Agent，可能调用模型和工具 |
| GET | `/api/agent/runs/{id}/events` | SSE 观察进度，不触发执行 |

## 请求示例

新增题目：

```json
{
  "title": "ArrayList 与 LinkedList 有什么区别？",
  "topic": "JAVA",
  "difficulty": "EASY",
  "referenceAnswer": "比较底层结构、随机访问、定位与插入成本。"
}
```

创建面试（`questionCount` 为 1～10，`topic` 可省略）：

```json
{"topic": "JAVA", "questionCount": 3}
```

提交回答：使用面试详情返回的实际 `turnId`，不能将题目 ID 当作轮次 ID。

```json
{"turnId": 1, "answer": "ArrayList 使用数组，LinkedList 使用双向链表……"}
```

导入文档（标题不超过 160 字符，正文不超过 6000 字符）：

```json
{"title": "集合资料", "content": "ArrayList 支持按下标快速访问……"}
```

导入只保存文档，随后调用文档索引接口；检索请求格式如下：

```json
{"query": "为什么不能直接认为频繁插入就选 LinkedList？"}
```

创建 Agent 运行：`requestId` 每次新任务生成一个 UUID；同一次创建请求重发时复用它。`sessionId` 可为 `null`，需要个人作答依据时填写实际面试 ID。

```json
{
  "requestId": "af4bc937-dc99-4b06-8ee4-d62060bffbd8",
  "prompt": "从题库选择两道 Java 题，并给出复习建议。",
  "sessionId": null
}
```

创建后用返回的运行 ID 调用执行接口；若执行响应丢失，先 GET 运行详情确认状态。SSE 重连只负责观察，不应触发重复执行。

## 状态与错误

非法参数、缺失记录、状态冲突和限流通过响应状态与错误内容表达。Agent 执行结果有一个特别约定：执行接口可以返回 HTTP 200，同时 `run.state` 表示运行失败；调用方必须检查运行状态，不能仅依据 HTTP 状态判断模型任务成功。存在等待要求时还会返回 `Retry-After`。

反馈失败时回答仍保留，可按页面提示手动重试。查询运行与订阅 SSE 不会消耗模型额度。没有可检索片段是正常的空命中结果，应结合检索状态判断，不能直接等同于网络或索引故障。
