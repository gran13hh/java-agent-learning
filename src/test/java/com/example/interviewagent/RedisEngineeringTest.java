package com.example.interviewagent;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.cache.QuestionPageCache;
import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.*;
import com.example.interviewagent.exception.*;
import com.example.interviewagent.repository.JdbcQuestionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis + MySQL；独立 UUID 限流桶，仅清理本测试创建的 key 和行，绝不 FLUSHDB。 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisEngineeringTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RedisRequestBudget budget;
    private String bucket;
    private final Set<String> keys = new HashSet<>();
    private final List<Long> questions = new ArrayList<>();
    private LettuceConnectionFactory connect(int port) {
        var f = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build());
        f.afterPropertiesSet(); f.start(); return f;
    }
    @BeforeEach void setup() {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource("config/application-local.yml"));
        String password = System.getenv("DB_PASSWORD");
        if (password == null) password = new StandardEnvironment().resolveRequiredPlaceholders(Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password"));
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL", "jdbc:mysql://127.0.0.1:3306/interview_agent?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai"), System.getenv().getOrDefault("DB_USERNAME", "interview_app"), password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        factory = connect(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))); redis = new StringRedisTemplate(factory);
        assertEquals("PONG", factory.getConnection().ping());
        bucket = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO ai_rate_buckets(provider) VALUES (?)", bucket);
        keys.add(rateKey("permits")); keys.add(rateKey("blocked"));
        budget = new RedisRequestBudget(redis, new JdbcRequestBudget(jdbc, manager));
    }
    private String rateKey(String suffix) { return "interview:v5:rate:{" + bucket + "}:" + suffix; }
    @AfterEach void cleanup() {
        if (jdbc != null) {
            for (long id : questions) jdbc.update("DELETE FROM questions WHERE id = ?", id);
            if (!questions.isEmpty()) jdbc.update("UPDATE cache_versions SET version = version + 1 WHERE name = 'questions'");
            jdbc.update("DELETE FROM ai_request_permits WHERE provider = ?", bucket);
            jdbc.update("DELETE FROM ai_rate_buckets WHERE provider = ?", bucket);
        }
        if (redis != null && !keys.isEmpty()) redis.delete(keys);
        if (factory != null) factory.destroy();
    }
    @Test void twoInstancesShareAtomicFiveRequestWindow() throws Exception {
        var other = new RedisRequestBudget(redis, new JdbcRequestBudget(jdbc, manager));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) { var limiter = i % 2 == 0 ? budget : other; jobs.add(executor.submit(() -> {
                gate.await(); try { limiter.acquire(bucket); return true; } catch (AiRateLimitException limited) { return false; }
            })); }
            gate.countDown(); int allowed = 0;
            for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals(5, allowed);
        }
        assertEquals(5L, redis.opsForZSet().zCard(rateKey("permits")));
        assertTrue(redis.getExpire(rateKey("permits")) > 0);
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM ai_request_permits WHERE provider = ?", Integer.class, bucket));
    }
    @Test void losingRedisKeysNeverResetsDurableQuotaOrUpstreamCooldown() {
        for (int i = 0; i < 5; i++) budget.acquire(bucket);
        redis.delete(rateKey("permits"));
        assertThrows(AiRateLimitException.class, () -> budget.acquire(bucket));
        budget.block(bucket, Duration.ofSeconds(120)); budget.block(bucket, Duration.ofSeconds(1));
        assertTrue(redis.getExpire(rateKey("blocked")) >= 118);
        redis.delete(List.of(rateKey("permits"), rateKey("blocked")));
        var limited = assertThrows(AiRateLimitException.class, () -> budget.acquire(bucket));
        assertTrue(limited.retryAfterSeconds() > 60);
    }
    @Test void expiredEntriesAreRemovedBeforeAdmission() {
        long old = System.currentTimeMillis() - 120000;
        for (int i = 0; i < 5; i++) redis.opsForZSet().add(rateKey("permits"), "old" + i, old);
        budget.acquire(bucket);
        assertEquals(1L, redis.opsForZSet().zCard(rateKey("permits")));
    }
    @Test void unavailableRedisFailsClosedButQuestionReadsFallBack() {
        var offline = connect(1); // 不监听的端口，不停止用户正在运行的 Redis。
        try {
            var template = new StringRedisTemplate(offline);
            var broken = new RedisRequestBudget(template, new JdbcRequestBudget(jdbc, manager));
            assertThrows(AiCallException.class, () -> broken.acquire(bucket));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_request_permits WHERE provider = ?", Integer.class, bucket));
            var result = new QuestionPageCache(template, jdbc).getOrLoad(null, 1, 5, () -> new PageResponse<>(List.of(), 1, 5, 0));
            assertTrue(result.items().isEmpty());
        } finally { offline.destroy(); }
    }
    @Test void cacheRoundTripsAndVersionChangeDefeatsLateStaleFill() {
        // 使用 size=20/JAVA 的当前版本 key；先删除该缓存只影响可重建的缓存，测试结束也删除。
        var cache = new QuestionPageCache(redis, jdbc);
        var repository = new JdbcQuestionRepository(jdbc);
        var reads = new AtomicInteger();
        java.util.function.Supplier<PageResponse<Question>> load = () -> { reads.incrementAndGet(); return new PageResponse<>(repository.findPage(Topic.JAVA,20,0),1,20,repository.count(Topic.JAVA)); };
        String oldKey = cacheKey(); keys.add(oldKey); redis.delete(oldKey);
        var first = cache.getOrLoad(Topic.JAVA,1,20,load);
        assertEquals(first, cache.getOrLoad(Topic.JAVA,1,20,load)); assertEquals(1, reads.get());
        String stale = redis.opsForValue().get(oldKey);
        assertTrue(redis.getExpire(oldKey) >= 59 && redis.getExpire(oldKey) <= 75);
        var created = new TransactionTemplate(manager).execute(status -> repository.insert(new CreateQuestionRequest("V5 缓存测试 " + UUID.randomUUID(),Topic.JAVA,Difficulty.EASY,"测试")));
        questions.add(created.id()); keys.add(cacheKey());
        // 模拟一个并发旧读在新增提交之后才写回，仍只能写进旧版本 key。
        redis.opsForValue().set(oldKey, stale, Duration.ofSeconds(60));
        var latest = cache.getOrLoad(Topic.JAVA,1,20,load);
        assertEquals(created.id(), latest.items().getFirst().id()); assertEquals(first.total()+1,latest.total());
        assertEquals(2,reads.get());
        redis.opsForValue().set(cacheKey(), "broken-json",Duration.ofSeconds(10));
        assertEquals(latest, cache.getOrLoad(Topic.JAVA,1,20,load)); assertEquals(3,reads.get());
    }
    private String cacheKey() { return "interview:v5:questions:" + jdbc.queryForObject("SELECT version FROM cache_versions WHERE name='questions'",Long.class) + ":JAVA:20"; }
    @Test void rolledBackQuestionDoesNotInvalidateVersion() {
        long before = jdbc.queryForObject("SELECT version FROM cache_versions WHERE name='questions'",Long.class);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            new JdbcQuestionRepository(jdbc).insert(new CreateQuestionRequest("回滚测试",Topic.JAVA,Difficulty.EASY,"测试")); status.setRollbackOnly();
        });
        assertEquals(before,jdbc.queryForObject("SELECT version FROM cache_versions WHERE name='questions'",Long.class));
    }
}
