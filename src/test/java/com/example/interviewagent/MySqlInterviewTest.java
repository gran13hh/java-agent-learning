package com.example.interviewagent;

import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.*;
import com.example.interviewagent.repository.*;
import com.example.interviewagent.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真正使用 Spring 事务代理和多个 MySQL 连接，验证内存替身无法证明的锁与回滚行为。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlInterviewTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private JdbcQuestionRepository questions;
    private JdbcInterviewRepository interviews;
    private InterviewService service;
    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> questionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("config/application-local.yml"));
            password = new StandardEnvironment().resolveRequiredPlaceholders(
                    Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password"));
        }
        dataSource = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL",
                "jdbc:mysql://127.0.0.1:3306/interview_agent?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai"),
                System.getenv().getOrDefault("DB_USERNAME", "interview_app"), password);
        jdbc = new JdbcTemplate(dataSource);
        questions = new JdbcQuestionRepository(jdbc);
        interviews = new JdbcInterviewRepository(jdbc);
        service = proxied(interviews, new MockAnswerFeedbackProvider());
        for (int i = 0; i < 2; i++) questionIds.add(questions.insert(new CreateQuestionRequest(
                "事务测试 " + UUID.randomUUID(), Topic.REDIS, Difficulty.EASY, "创建时的参考答案")).id());
    }

    private InterviewService proxied(JdbcInterviewRepository repository, AnswerFeedbackProvider provider) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new InterviewService(repository, questions, provider));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        return (InterviewService) proxy.getProxy();
    }

    private InterviewView create(int count) {
        var created = service.create(new CreateInterviewRequest(Topic.REDIS, count));
        sessionIds.add(created.id());
        return created;
    }

    @AfterEach
    void cleanupOnlyOwnedFixtures() {
        if (jdbc == null) return;
        // 并发测试需要提交；只按本测试获得的主键清理，绝不 TRUNCATE 或清空用户题库。
        for (long id : sessionIds) {
            jdbc.update("DELETE FROM interview_turns WHERE session_id = ?", id);
            jdbc.update("DELETE FROM interview_sessions WHERE id = ?", id);
        }
        for (long id : questionIds) jdbc.update("DELETE FROM questions WHERE id = ?", id);
    }

    @Test
    void snapshotsSurviveQuestionChangesAndNewServiceInstanceResumes() {
        var created = create(2);
        jdbc.update("UPDATE questions SET reference_answer = '修改后的答案' WHERE id = ?", questionIds.getLast());
        var saved = service.answer(created.id(), new SubmitAnswerRequest(created.turns().getFirst().id(), "测试回答 ' 🤖"));
        assertEquals("创建时的参考答案", saved.turns().getFirst().referenceAnswer());
        var freshService = proxied(new JdbcInterviewRepository(new JdbcTemplate(dataSource)), new MockAnswerFeedbackProvider());
        assertEquals(saved, freshService.get(created.id()));
        assertEquals(1, saved.answeredCount());
    }

    @Test
    void failureAfterSavingAnswerRollsBackBothAnswerAndCompletion() {
        var created = create(1);
        var failingRepository = new JdbcInterviewRepository(jdbc) {
            @Override public void complete(long id) {
                super.complete(id);
                throw new IllegalStateException("故意模拟事务最后一步失败");
            }
        };
        var failing = proxied(failingRepository, new MockAnswerFeedbackProvider());
        assertThrows(IllegalStateException.class, () -> failing.answer(created.id(),
                new SubmitAnswerRequest(created.turns().getFirst().id(), "应回滚的回答")));
        var found = service.get(created.id());
        assertEquals(0, found.answeredCount());
        assertEquals(InterviewStatus.IN_PROGRESS, found.status());
        assertNull(found.turns().getFirst().feedback());
    }

    @Test
    void twoConcurrentRetriesGenerateFeedbackAndCompleteOnlyOnce() throws Exception {
        var created = create(1);
        var calls = new AtomicInteger();
        var enteredProvider = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        var concurrentService = proxied(interviews, (question, answer) -> {
            calls.incrementAndGet();
            enteredProvider.countDown();
            try {
                if (!releaseProvider.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待测试释放超时");
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return new AnswerFeedbackProvider.Feedback("MOCK", "并发测试反馈");
        });
        var input = new SubmitAnswerRequest(created.turns().getFirst().id(), "同一个回答");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentService.answer(created.id(), input));
            assertTrue(enteredProvider.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> concurrentService.answer(created.id(), input));
            try {
                // 第一事务持有父会话行锁时，第二个提交不能完成。
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            } finally { releaseProvider.countDown(); }
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { releaseProvider.countDown(); }
        assertEquals(1, calls.get());
        assertEquals(1, service.get(created.id()).answeredCount());
        assertEquals(InterviewStatus.COMPLETED, service.get(created.id()).status());
    }
}
