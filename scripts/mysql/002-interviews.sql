USE interview_agent;

-- 增量建表：保留已有 questions 和练习记录；应用账号无需获得建表权限。
CREATE TABLE IF NOT EXISTS interview_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(20) NULL,
    question_count INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT chk_session_topic CHECK (topic IS NULL OR topic IN ('JAVA', 'MYSQL', 'REDIS', 'AGENT')),
    CONSTRAINT chk_session_count CHECK (question_count BETWEEN 1 AND 10),
    CONSTRAINT chk_session_status CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL) OR
        (status = 'COMPLETED' AND completed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS interview_turns (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    position INT NOT NULL,
    question_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    topic VARCHAR(20) NOT NULL,
    difficulty VARCHAR(10) NOT NULL,
    reference_answer TEXT NOT NULL,
    answer TEXT NULL,
    feedback TEXT NULL,
    feedback_mode VARCHAR(20) NULL,
    answered_at DATETIME(6) NULL,
    CONSTRAINT fk_turn_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id),
    CONSTRAINT uk_turn_position UNIQUE (session_id, position),
    CONSTRAINT chk_turn_position CHECK (position BETWEEN 1 AND 10),
    CONSTRAINT chk_turn_answer CHECK (
        (answer IS NULL AND feedback IS NULL AND feedback_mode IS NULL AND answered_at IS NULL) OR
        (answer IS NOT NULL AND feedback IS NOT NULL AND feedback_mode IS NOT NULL AND answered_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- question_id 保留来源编号，但不建立外键：历史记录使用创建时的题目快照。
