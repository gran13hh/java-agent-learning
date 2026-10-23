# 题库数据

基础题是应用的演示数据，供题库筛选、模拟面试和 Agent 选题使用。题目与参考要点保存在 [007-basic-questions.sql](../scripts/mysql/007-basic-questions.sql)，共 60 道，四个主题各 15 道，难度均为 `EASY`。

| 主题 | 内容 |
| --- | --- |
| JAVA | 类型、相等性、字符串、集合、异常、接口、Spring 注入、分层、DTO、HTTP 与并发 |
| MYSQL | 表与关联、聚合、NULL、索引、事务、隔离、MVCC、锁、参数绑定、连接池与分页 |
| REDIS | 数据结构、缓存命中、TTL、回源、一致性、缓存故障、限流、Lua、持久化与锁 |
| AGENT | 工作流、Prompt、Token、结构化输出、RAG、Embedding、分块、评测、工具、预算、提示注入与幂等 |

参考答案给出核心概念、适用场景与容易混淆的边界，不要求用户逐字复述。题库参考答案和 RAG 资料库是不同数据：补充题目不会自动新增知识片段或调用 Embedding 模型。

## 导入

全新数据库运行 `python3 scripts/setup_db.py`，会初始化全部表、原有 3 道入门示例和本批基础题，共 63 道。已有数据库运行 `python3 scripts/migrate_db.py` 也会补充基础题。

若现有数据库已完成建表，只补充题目即可；在项目根目录使用应用账号执行（密码按提示输入）：

```bash
mysql --protocol=TCP --host=127.0.0.1 --port=3306 \
  --user=interview_app --password --default-character-set=utf8mb4 \
  < scripts/mysql/007-basic-questions.sql
```

如果 `mysql` 不在 PATH 中，使用本机 MySQL 客户端的实际路径。此脚本依赖 `questions` 和 `cache_versions` 表，不需要给应用账号增加建表权限。

脚本按“主题 + 标题”跳过已存在题目，不改写现有答案或面试记录；重复运行不会重复添加。新增题目与题库缓存版本递增在同一事务中完成，无新增时版本不变。多个本脚本实例通过版本行锁串行化；这并不为所有自定义写入建立全局的标题唯一约束。数据库导入失败时应先排查错误，再重新运行。

题目按主题交错插入，以便默认按最新 ID 排序的首页展示多种主题。各主题有至少 15 道基础题，可创建 10 题的主题面试。当前面试仍按最新 N 题选取；增加题量不会将其变成随机抽题。

## 概念核对

题目采用自行整理的参考要点。涉及具体版本或边界时，可结合项目实现与官方文档核对：

- [Java 21 HashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html)：散列、顺序与并发约定。
- [MySQL 8.4 InnoDB 隔离级别](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)：事务隔离与一致性读。
- [Redis 数据类型](https://redis.io/docs/latest/develop/data-types/)：常用类型与适用操作。
- [Spring AI 工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)：模型请求工具、应用负责执行的边界；在线文档版本可能与项目锁定版本不同。
