USE interview_agent;

-- 短事务认领反馈任务；租约允许进程中断后恢复，token 防止旧请求覆盖新结果。
CREATE TABLE IF NOT EXISTS ai_feedback_tasks (
    turn_id BIGINT NOT NULL PRIMARY KEY,
    token CHAR(36) NOT NULL,
    state VARCHAR(20) NOT NULL,
    lease_until DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_feedback_turn FOREIGN KEY (turn_id) REFERENCES interview_turns(id),
    CONSTRAINT chk_feedback_state CHECK (state IN ('RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每个站点一行锁；请求记录跨线程、跨本应用进程和重启保留。
CREATE TABLE IF NOT EXISTS ai_rate_buckets (
    provider VARCHAR(80) NOT NULL PRIMARY KEY,
    blocked_until DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO ai_rate_buckets(provider) VALUES ('chat'), ('embedding');

CREATE TABLE IF NOT EXISTS ai_request_permits (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    INDEX idx_permit_provider_time (provider, reserved_at),
    CONSTRAINT fk_permit_bucket FOREIGN KEY(provider) REFERENCES ai_rate_buckets(provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
