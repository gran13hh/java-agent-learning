USE interview_agent;

-- 原文与向量都保存在已有 MySQL；向量相似度由 Java 计算，不需要额外向量数据库。
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    embedding_identity CHAR(64) NULL,
    token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    message VARCHAR(300) NOT NULL DEFAULT '原文已保存，等待建立索引',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_knowledge_state CHECK (state IN ('PENDING','INDEXING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    position INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    content TEXT NOT NULL,
    embedding BLOB NOT NULL,
    UNIQUE KEY uk_document_position(document_id, position),
    CONSTRAINT fk_chunk_document FOREIGN KEY(document_id) REFERENCES knowledge_documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
