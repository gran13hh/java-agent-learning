package com.example.interviewagent.knowledge;

import com.example.interviewagent.ai.VectorEncoder;
import com.example.interviewagent.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class KnowledgeService {
    public static final double MIN_SCORE = 0.35;
    public static final int TOP_K = 3;
    private final KnowledgeRepository repository;
    private final VectorEncoder encoder;
    public KnowledgeService(KnowledgeRepository repository, VectorEncoder encoder) { this.repository = repository; this.encoder = encoder; }
    public record Summary(long id, String title, String state, String message, int chunkCount, boolean compatible, LocalDateTime createdAt) {}
    public record Detail(Summary document, String content, List<KnowledgeText.Part> chunks) {}
    public record Hit(String sourceId, long documentId, String title, int position, int start, int end, double score, String content) {}
    public record SearchResult(String message, List<Hit> hits) {}

    private Summary summary(KnowledgeRepository.Document doc) {
        return new Summary(doc.id(), doc.title(), doc.state(), doc.message(), doc.chunkCount(),
                encoder.embeddingIdentity().equals(doc.embeddingIdentity()), doc.createdAt());
    }
    public List<Summary> list() { return repository.list().stream().map(this::summary).toList(); }
    public Detail get(long id) {
        var doc = repository.get(id);
        return new Detail(summary(doc), doc.content(), KnowledgeText.split(doc.content()));
    }
    public Summary save(String title, String content) {
        if (title == null || title.isBlank() || title.strip().length() > 160) throw new InvalidRequestException("标题需要 1～160 字符");
        return summary(repository.save(title.strip(), KnowledgeText.normalize(content)));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Summary index(long id) {
        var claim = repository.claim(id, encoder.embeddingIdentity());
        if (claim == null) return summary(repository.get(id));
        try {
            var parts = KnowledgeText.split(claim.document().content());
            // 最多 9 个片段，一次批量请求；不在循环中逐片请求，珍惜每分钟 5 次额度。
            var vectors = encoder.embed(parts.stream().map(KnowledgeText.Part::content).toList());
            if (!repository.finish(claim, parts, vectors)) throw new InterviewConflictException("本次索引任务已被后续任务替代，请刷新资料");
        } catch (AiRateLimitException exception) {
            repository.fail(claim, "向量接口已限流，原文保留；稍后手动重试"); throw exception;
        } catch (RuntimeException exception) {
            repository.fail(claim, "索引失败，原文保留；请检查向量配置后手动重试");
            if (exception instanceof InterviewConflictException conflict) throw conflict;
            throw new AiCallException("资料索引失败，原文已保存，可手动重试");
        }
        return summary(repository.get(id));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SearchResult search(String query) {
        if (query == null || query.isBlank() || query.length() > 1000) throw new InvalidRequestException("检索问题需要 1～1000 字符");
        var candidates = repository.candidates(encoder.embeddingIdentity());
        if (candidates.isEmpty()) return new SearchResult("没有与当前向量模型兼容的就绪资料，未调用向量接口。", List.of());
        float[] vector = encoder.embed(List.of(query.strip())).getFirst();
        var hits = candidates.stream().map(c -> new Hit("K" + c.id(), c.documentId(), c.title(), c.position(), c.start(), c.end(),
                        KnowledgeText.cosine(vector, c.vector()), c.content()))
                .filter(hit -> hit.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::sourceId))
                .limit(TOP_K).toList();
        return new SearchResult(hits.isEmpty() ? "未检索到达到阈值的片段；这不代表知识库中一定没有答案。" : "已召回相关片段，相关度不等于事实正确率。", hits);
    }
}
