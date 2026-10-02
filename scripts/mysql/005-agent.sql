USE interview_agent;

CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) NOT NULL UNIQUE,
    prompt TEXT NOT NULL,
    session_id BIGINT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    result TEXT NULL,
    message VARCHAR(300) NOT NULL DEFAULT '已保存，等待执行',
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    retry_after_seconds INT NOT NULL DEFAULT 0,
    token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT fk_agent_interview FOREIGN KEY(session_id) REFERENCES interview_sessions(id),
    CONSTRAINT chk_agent_state CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','RATE_LIMITED','LIMIT_REACHED','TIMED_OUT','INTERRUPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_steps (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    position INT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    name VARCHAR(80) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'STARTED',
    input_json TEXT NOT NULL,
    output_json MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uk_agent_position(run_id, position),
    CONSTRAINT fk_agent_step FOREIGN KEY(run_id) REFERENCES agent_runs(id),
    CONSTRAINT chk_agent_step_kind CHECK (kind IN ('MODEL','TOOL')),
    CONSTRAINT chk_agent_step_state CHECK (state IN ('STARTED','SUCCEEDED','REJECTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
