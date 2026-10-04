USE interview_agent;
CREATE TABLE IF NOT EXISTS cache_versions (
    name VARCHAR(40) NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT IGNORE INTO cache_versions(name,version) VALUES ('questions',0);
