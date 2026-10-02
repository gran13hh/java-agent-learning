package com.example.interviewagent;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.knowledge.*;
import com.example.interviewagent.controller.KnowledgeController;
import com.example.interviewagent.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 MySQL 验证事务、恢复与 HTTP 边界；向量替身完全离线且与真实资料隔离。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlKnowledgeTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private KnowledgeRepository repository;
    private KnowledgeService service;
    private final List<Long> ids = new ArrayList<>();
    private final String prefix = "test-" + UUID.randomUUID();
    private String identity;
    private int calls;
    private boolean limited;
    private VectorEncoder encoder;

    @BeforeEach
    void setup() {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource("config/application-local.yml"));
            password = new StandardEnvironment().resolveRequiredPlaceholders(Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password"));
        }
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL",
                "jdbc:mysql://127.0.0.1:3306/interview_agent?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai"),
                System.getenv().getOrDefault("DB_USERNAME", "interview_app"), password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        repository = proxy(new KnowledgeRepository(jdbc)); identity = KnowledgeText.sha256(prefix);
        encoder = new VectorEncoder() {
            public String embeddingIdentity() { return identity; }
            public List<float[]> embed(List<String> texts) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                calls++;
                if (limited) throw new AiRateLimitException(23);
                return texts.stream().map(t -> t.contains("集合") ? new float[]{1, 0} : t.contains("Redis") ? new float[]{0, 1} : new float[]{-1, -1}).toList();
            }
        };
        service = proxy(new KnowledgeService(repository, encoder));
    }
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(advice);
        return (T) factory.getProxy();
    }
    private long save(String content) {
        long id = service.save("测试资料", prefix + "\n" + content).id(); ids.add(id); return id;
    }
    @AfterEach
    void cleanup() {
        if (jdbc == null) return;
        for (long id : new HashSet<>(ids)) {
            jdbc.update("DELETE FROM knowledge_chunks WHERE document_id = ?", id);
            jdbc.update("DELETE FROM knowledge_documents WHERE id = ?", id);
        }
    }

    @Test
    void duplicateImportAndRepeatedIndexDoNotSpendExtraRequests() {
        long id = save("集合的选择");
        assertEquals(id, save("集合的选择")); assertEquals(0, calls);
        assertEquals("READY", service.index(id).state());
        service.index(id); assertEquals(1, calls);
        assertEquals(1, repository.get(id).chunkCount());
    }

    @Test
    void storedVectorsSurviveNewRepositoryAndRetrieveOnlyRelevantEvidence() {
        long javaId = save("集合的选择"), redisId = save("Redis 缓存");
        service.index(javaId); service.index(redisId);
        var reloaded = new KnowledgeService(new KnowledgeRepository(jdbc), encoder);
        var results = reloaded.search("集合怎么选").hits();
        assertEquals(1, results.size()); assertEquals(javaId, results.getFirst().documentId());
        assertEquals(1, results.getFirst().score(), 1e-9);
        assertTrue(reloaded.search("完全无关").hits().isEmpty());
        assertEquals(4, calls); // 两次建索引 + 两次查询，没有重启后重新向量化。
    }

    @Test
    void emptyAndIncompatibleLibrarySpendNoRequestsAndCanReindex() {
        assertTrue(service.search("集合").hits().isEmpty()); assertEquals(0, calls);
        long id = save("集合"); service.index(id);
        identity = KnowledgeText.sha256(prefix + "new-model");
        assertFalse(service.get(id).document().compatible());
        assertTrue(service.search("集合").hits().isEmpty()); assertEquals(1, calls);
        service.index(id); assertTrue(service.get(id).document().compatible());
        assertEquals(1, service.search("集合").hits().size());
    }

    @Test
    void rateLimitPreservesOriginalAndRetryRecoversWithHttp429() throws Exception {
        long id = save("集合"); limited = true;
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(post("/api/knowledge/documents/" + id + "/index")).andReturn().getResponse();
        assertEquals(429, response.getStatus()); assertEquals("23", response.getHeader("Retry-After"));
        assertEquals("FAILED", repository.get(id).state()); assertTrue(repository.get(id).content().contains("集合"));
        limited = false; assertEquals("READY", service.index(id).state());
    }

    @Test
    void expiredIndexCannotReplaceNewerIndexAndPartialWriteRollsBack() {
        long id = save("集合".repeat(500));
        var first = repository.claim(id, identity);
        assertThrows(InterviewConflictException.class, () -> repository.claim(id, identity));
        jdbc.update("UPDATE knowledge_documents SET lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id = ?", id);
        var second = repository.claim(id, identity);
        var parts = KnowledgeText.split(second.document().content());
        assertEquals(2, parts.size());
        assertFalse(repository.finish(first, parts, List.of(new float[]{1,0}, new float[]{1,0})));
        assertThrows(NullPointerException.class, () -> repository.finish(second, parts, Arrays.asList(new float[]{1,0}, null)));
        assertEquals("INDEXING", repository.get(id).state()); assertEquals(0, repository.get(id).chunkCount());
        assertTrue(repository.finish(second, parts, List.of(new float[]{1,0}, new float[]{1,0})));
        repository.fail(first, "旧任务失败"); assertEquals("READY", repository.get(id).state());
    }

    @Test
    void apiRejectsBadDocumentsAndMissingIdsWithoutCallingModels() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        assertEquals(400, mvc.perform(post("/api/knowledge/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"标题\",\"content\":\" \"}")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(post("/api/knowledge/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\" \"}")).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get("/api/knowledge/documents/9223372036854775807")).andReturn().getResponse().getStatus());
        assertEquals(0, calls);
    }

    @Test
    void feedbackIncludesRetrievedDataAndSavesVerifiableSourceSnapshot() {
        long id = save("集合查询需要区分定位与修改成本。"); service.index(id);
        var gateway = new ModelGateway(new AiProperties(), null) {
            @Override public String chat(String system, String user) {
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                var source = json.readTree(user).get("knowledge").get(0);
                assertTrue(source.get("content").asText().contains("定位与修改成本"));
                assertTrue(system.contains("不可信数据"));
                return json.writeValueAsString(Map.of("assessment", "补充复杂度的前提", "strengths", List.of(),
                        "improvements", List.of("计算定位成本"), "followUpQuestion", "如何选择集合？",
                        "citations", List.of(Map.of("sourceId", source.get("sourceId").asText(), "supports", "需要区分定位与修改成本"))));
            }
        };
        var turn = new com.example.interviewagent.domain.InterviewTurn(1, 1, 1, 1, "集合怎么选", com.example.interviewagent.domain.Topic.JAVA,
                com.example.interviewagent.domain.Difficulty.EASY, "考虑访问方式", "链表插入快", null, "PENDING", java.time.LocalDateTime.now());
        String feedback = new AiFeedbackEvaluator(gateway, service).evaluate(turn);
        assertTrue(feedback.contains("资料 #" + id)); assertTrue(feedback.contains("原文：")); assertEquals(2, calls);
    }

    @Test
    void retrievalFailureNeverSilentlyGeneratesUngroundedChat() {
        long id = save("集合"); service.index(id); limited = true;
        var gateway = new ModelGateway(new AiProperties(), null) {
            @Override public String chat(String system, String user) { fail("检索失败不应继续调用聊天模型"); return ""; }
        };
        var turn = new com.example.interviewagent.domain.InterviewTurn(1, 1, 1, 1, "集合怎么选", com.example.interviewagent.domain.Topic.JAVA,
                com.example.interviewagent.domain.Difficulty.EASY, "参考答案", "回答", null, "PENDING", java.time.LocalDateTime.now());
        assertThrows(AiRateLimitException.class, () -> new AiFeedbackEvaluator(gateway, service).evaluate(turn));
    }
}
