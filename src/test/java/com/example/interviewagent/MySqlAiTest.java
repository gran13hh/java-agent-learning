package com.example.interviewagent;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.*;
import com.example.interviewagent.exception.*;
import com.example.interviewagent.repository.*;
import com.example.interviewagent.service.*;
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
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlAiTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private JdbcRequestBudget budget;
    private String bucket;
    private InterviewService interviews;
    private FeedbackTransactions feedback;
    private Long sessionId;
    private long questionId;

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
        budget = new JdbcRequestBudget(jdbc, manager); bucket = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_rate_buckets(provider) VALUES (?)", bucket);
        var repository = new JdbcInterviewRepository(jdbc); var questions = new JdbcQuestionRepository(jdbc);
        questionId = questions.insert(new CreateQuestionRequest("AI 事务测试 " + UUID.randomUUID(), Topic.REDIS, Difficulty.EASY, "参考答案")).id();
        interviews = proxy(new InterviewService(repository, questions, new PendingAnswerFeedbackProvider()));
        feedback = proxy(new FeedbackTransactions(jdbc, repository));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(advice);
        return (T) factory.getProxy();
    }

    private long answeredTurn() {
        var created = interviews.create(new CreateInterviewRequest(Topic.REDIS, 1)); sessionId = created.id();
        long turn = created.turns().getFirst().id(); interviews.answer(sessionId, new SubmitAnswerRequest(turn, "已保存的回答")); return turn;
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null) return;
        if (sessionId != null) {
            jdbc.update("DELETE FROM ai_feedback_tasks WHERE turn_id IN (SELECT id FROM interview_turns WHERE session_id = ?)", sessionId);
            jdbc.update("DELETE FROM interview_turns WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM interview_sessions WHERE id = ?", sessionId);
        }
        jdbc.update("DELETE FROM questions WHERE id = ?", questionId);
        jdbc.update("DELETE FROM ai_request_permits WHERE provider = ?", bucket);
        jdbc.update("DELETE FROM ai_rate_buckets WHERE provider = ?", bucket);
    }

    @Test
    void windowPersistsAcrossNewLimiterInstancesAndExpires() {
        for (int i = 0; i < 5; i++) budget.acquire(bucket);
        var reloaded = new JdbcRequestBudget(jdbc, manager);
        assertThrows(AiRateLimitException.class, () -> reloaded.acquire(bucket));
        jdbc.update("UPDATE ai_request_permits SET reserved_at = TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP(6)) WHERE provider = ?", bucket);
        assertDoesNotThrow(() -> reloaded.acquire(bucket));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_request_permits WHERE provider = ?", Integer.class, bucket));
    }

    @Test
    void parallelCallsNeverExceedFiveAndBlockedUntilSurvivesReload() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 10; i++) futures.add(executor.submit(() -> {
                start.await();
                try { budget.acquire(bucket); return true; } catch (AiRateLimitException exception) { return false; }
            }));
            start.countDown(); int allowed = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals(5, allowed);
        }
        budget.block(bucket, java.time.Duration.ofSeconds(120));
        var error = assertThrows(AiRateLimitException.class, () -> new JdbcRequestBudget(jdbc, manager).acquire(bucket));
        assertTrue(error.retryAfterSeconds() > 60);
    }

    @Test
    void modelRunsOutsideTransactionAndSuccessfulFeedbackIsNotRegenerated() {
        long turn = answeredTurn(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = proxy(new AiFeedbackService(feedback, t -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet(); return "AI 反馈";
        }, interviews));
        assertEquals("AI", service.generate(sessionId, turn).turns().getFirst().feedbackMode());
        service.generate(sessionId, turn); assertEquals(1, calls.get());
    }

    @Test
    void failedModelKeepsAnswerAndCanBeRetried() {
        long turn = answeredTurn();
        var service = proxy(new AiFeedbackService(feedback, t -> { throw new AiRateLimitException(60); }, interviews));
        assertThrows(AiRateLimitException.class, () -> service.generate(sessionId, turn));
        var saved = interviews.get(sessionId).turns().getFirst();
        assertEquals("已保存的回答", saved.answer()); assertEquals("RATE_LIMITED", saved.feedbackMode());
        var working = proxy(new AiFeedbackService(feedback, t -> "恢复后的反馈", interviews));
        assertEquals("AI", working.generate(sessionId, turn).turns().getFirst().feedbackMode());
    }

    @Test
    void duplicateInFlightClaimIsRejectedAndExpiredResultCannotOverwriteNewClaim() {
        long turn = answeredTurn(); var first = feedback.claim(sessionId, turn);
        assertThrows(InterviewConflictException.class, () -> feedback.claim(sessionId, turn));
        jdbc.update("UPDATE ai_feedback_tasks SET lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE turn_id = ?", turn);
        var second = feedback.claim(sessionId, turn);
        feedback.finish(first, "过期结果", "AI");
        assertEquals("PROCESSING", interviews.get(sessionId).turns().getFirst().feedbackMode());
        feedback.finish(second, "最新结果", "AI");
        assertEquals("最新结果", interviews.get(sessionId).turns().getFirst().feedback());
    }
}
