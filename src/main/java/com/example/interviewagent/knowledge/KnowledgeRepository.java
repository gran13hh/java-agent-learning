package com.example.interviewagent.knowledge;

import com.example.interviewagent.exception.InterviewConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    public KnowledgeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Document(long id, String title, String content, String state, String embeddingIdentity,
                           String message, LocalDateTime createdAt, int chunkCount) {}
    public record Chunk(long id, long documentId, String title, int position, int start, int end, String content, float[] vector) {}
    public record Claim(Document document, String token, String identity) {}

    private static final String SELECT = """
            SELECT d.*, (SELECT COUNT(*) FROM knowledge_chunks c WHERE c.document_id = d.id) AS chunk_count
            FROM knowledge_documents d
            """;
    private Document map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Document(rs.getLong("id"), rs.getString("title"), rs.getString("content"), rs.getString("state"),
                rs.getString("embedding_identity"), rs.getString("message"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getInt("chunk_count"));
    }

    @Transactional
    public Document save(String title, String content) {
        String hash = KnowledgeText.sha256(content);
        // 唯一索引保证并发重复导入只保留一份，沿用第一次导入的标题；保存原文不调用模型。
        jdbc.update("INSERT INTO knowledge_documents(title,content,content_hash) VALUES (?,?,?) ON DUPLICATE KEY UPDATE content_hash = ?",
                title, content, hash, hash);
        return jdbc.query(SELECT + " WHERE d.content_hash = ?", this::map, hash).getFirst();
    }
    public Document get(long id) {
        return jdbc.query(SELECT + " WHERE d.id = ?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资料不存在"));
    }
    public List<Document> list() { return jdbc.query(SELECT + " ORDER BY d.id DESC LIMIT 100", this::map); }

    @Transactional
    public Claim claim(long id, String identity) {
        var locked = jdbc.query("SELECT id FROM knowledge_documents WHERE id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), id);
        if (locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资料不存在");
        var doc = get(id);
        if (doc.state().equals("READY") && identity.equals(doc.embeddingIdentity())) return null;
        Boolean active = jdbc.queryForObject("SELECT state = 'INDEXING' AND lease_until > CURRENT_TIMESTAMP(6) FROM knowledge_documents WHERE id = ?", Boolean.class, id);
        if (Boolean.TRUE.equals(active)) throw new InterviewConflictException("资料正在建立索引；若进程中断，两分钟后可手动重试");
        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE knowledge_documents SET state = 'INDEXING', token = ?, lease_until = TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)), message = '正在建立索引' WHERE id = ?", token, id);
        return new Claim(doc, token, identity);
    }

    @Transactional
    public boolean finish(Claim claim, List<KnowledgeText.Part> parts, List<float[]> vectors) {
        if (parts.size() != vectors.size() || parts.isEmpty()) throw new IllegalArgumentException("分块与向量不匹配");
        int changed = jdbc.update("""
                UPDATE knowledge_documents SET state = 'READY', embedding_identity = ?, lease_until = NULL, message = '索引已就绪'
                WHERE id = ? AND token = ? AND state = 'INDEXING'
                """, claim.identity(), claim.document().id(), claim.token());
        if (changed == 0) return false; // 过期任务的晚到结果不能覆盖新索引。
        jdbc.update("DELETE FROM knowledge_chunks WHERE document_id = ?", claim.document().id());
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            jdbc.update("INSERT INTO knowledge_chunks(document_id,position,start_offset,end_offset,content,embedding) VALUES (?,?,?,?,?,?)",
                    claim.document().id(), part.position(), part.start(), part.end(), part.content(), KnowledgeText.encode(vectors.get(i)));
        }
        return true; // 状态与所有分块同一事务提交，检索不会读到半份文档。
    }

    @Transactional
    public void fail(Claim claim, String message) {
        jdbc.update("UPDATE knowledge_documents SET state = 'FAILED', lease_until = NULL, message = ? WHERE id = ? AND token = ? AND state = 'INDEXING'",
                message, claim.document().id(), claim.token());
    }

    public List<Chunk> candidates(String identity) {
        // 每次查询从持久化数据构建内存快照；不维护第二份可变缓存，避免重启/多实例的索引失效问题。
        var chunks = jdbc.query("""
                SELECT c.*, d.title FROM knowledge_chunks c JOIN knowledge_documents d ON d.id = c.document_id
                WHERE d.state = 'READY' AND d.embedding_identity = ? ORDER BY c.id LIMIT 2001
                """, (rs, n) -> new Chunk(rs.getLong("id"), rs.getLong("document_id"), rs.getString("title"),
                rs.getInt("position"), rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("content"), KnowledgeText.decode(rs.getBytes("embedding"))), identity);
        if (chunks.size() > 2000) throw new InterviewConflictException("当前教学版支持最多 2000 个可检索片段，请扩展索引后再检索");
        return chunks;
    }
}
