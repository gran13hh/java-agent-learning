-- 此文件由管理员运行；应用账号不拥有建表、删表或管理其他账号的权限。
CREATE DATABASE IF NOT EXISTS interview_agent
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE interview_agent;

CREATE TABLE IF NOT EXISTS questions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    topic VARCHAR(20) NOT NULL,
    difficulty VARCHAR(10) NOT NULL,
    reference_answer TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_questions_topic_id (topic, id),
    CONSTRAINT chk_question_topic CHECK (topic IN ('JAVA', 'MYSQL', 'REDIS', 'AGENT')),
    CONSTRAINT chk_question_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'))
) ENGINE=InnoDB;

-- 只有空表才插入入门题；重复初始化不清空已有数据，也不重复插入示例。
INSERT INTO questions (title, topic, difficulty, reference_answer)
SELECT seed.title, seed.topic, seed.difficulty, seed.answer
FROM (
    SELECT 'Java 接口和实现类分别承担什么职责？' AS title, 'JAVA' AS topic, 'EASY' AS difficulty,
           '接口描述能力契约，实现类提供具体行为。构造器注入让业务依赖接口，便于替换实现和测试。' AS answer
    UNION ALL
    SELECT '为什么数据库查询要使用绑定参数？', 'MYSQL', 'EASY',
           '绑定参数让输入作为数据处理，避免把用户输入拼接为 SQL 语法；动态表名等标识符仍需白名单。'
    UNION ALL
    SELECT '模型提出工具调用后，谁实际执行工具？', 'AGENT', 'EASY',
           '应用校验工具名称与参数并执行代码，再把结果返回模型；模型本身不直接访问数据库。'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM questions);
