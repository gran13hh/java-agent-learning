# 测试与评测

所有命令在项目根目录运行。`-o` 表示 Maven 离线模式，要求所需依赖已经缓存；首次构建见 [README](../README.md)。

## 自动化测试

```bash
# 不依赖 MySQL、Redis 或真实模型的测试；集成测试按条件跳过。
./scripts/mvn.sh -o -B -ntp test

# 加入真实 MySQL 集成测试；读取本地配置或 DB_PASSWORD。
./scripts/test-mysql.sh -o

# 加入 Redis + MySQL 测试，需要两个服务均已启动。
./scripts/test-all.sh -o
```

测试覆盖参数校验、事务与面试状态、幂等、引用校验、索引恢复、Agent 预算、缓存版本、并发限流和 SSE 连接清理。模型适配使用本地 HTTP 替身，常规自动化测试不请求外部模型。集成测试会创建并清理测试记录，应在本地开发数据库执行。

## 固定 Agent 评测

用例位于 [cases.json](evaluation/cases.json)，固定语料位于 [java-collections.md](examples/java-collections.md)。两者是评测脚本的输入，随仓库保留。

启动应用并配置真实聊天、向量模型和 Redis 后，可依次执行：

```bash
# 只列出用例，不请求应用或外部模型。
python3 scripts/evaluate.py

# 导入固定语料并建立索引；可能调用向量 API。
python3 scripts/evaluate.py --prepare

# 执行全部用例，会调用真实模型并消耗额度。
python3 scripts/evaluate.py --execute

# 或单独执行一个用例。
python3 scripts/evaluate.py --execute --case knowledge-grounding

# 根据已有报告中的运行 ID 重新读取结果，不重跑模型。
python3 scripts/evaluate.py --report data/evaluation/<report>.json
```

`--prepare` 每次导入文档，通常只需准备一次。三个用例分别检查题库工具、资料召回和未选择面试时的证据边界。全部执行时用例间隔 65 秒；外部请求仍由应用统一限流，与其他页面调用共用额度。

结果写入 Git 忽略的 `data/evaluation/`。机械检查包括运行完成、必要工具被成功调用、调用预算和目标片段召回；人工仍需核对引用是否支持结论、是否虚构个人作答，以及建议是否正确。少量冒烟用例不能代表总体准确率或性能指标。
