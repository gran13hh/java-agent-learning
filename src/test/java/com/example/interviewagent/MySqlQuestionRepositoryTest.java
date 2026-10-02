package com.example.interviewagent;

import com.example.interviewagent.domain.Difficulty;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import com.example.interviewagent.repository.JdbcQuestionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式开启后使用真实 MySQL；每个测试都回滚，不删除或覆盖已有题目。
 * 注意：自增 id 即使回滚也可能留下空洞，这是 MySQL 正常行为。
 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlQuestionRepositoryTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcQuestionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://127.0.0.1:3306/interview_agent"
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai");
        String password = System.getenv("DB_PASSWORD");
        // 复用已在类路径中的 YAML 解析库，不为测试另装 Python 包或新依赖。
        var localConfig = new FileSystemResource("config/application-local.yml");
        if ((password == null || password.isBlank()) && localConfig.exists()) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(localConfig);
            var properties = yaml.getObject();
            if (properties != null) {
                String configured = properties.getProperty("spring.datasource.password");
                if (configured != null) {
                    password = new StandardEnvironment().resolveRequiredPlaceholders(configured);
                }
            }
        }
        assertNotNull(password, "请配置本地 YAML 或通过交互脚本设置 DB_PASSWORD");
        dataSource = new SingleConnectionDataSource(url,
                System.getenv().getOrDefault("DB_USERNAME", "interview_app"), password, true);
        dataSource.getConnection().setAutoCommit(false);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcQuestionRepository(jdbc);
    }

    @AfterEach
    void rollback() throws Exception {
        if (dataSource != null) {
            try {
                dataSource.getConnection().rollback();
            } finally {
                dataSource.destroy();
            }
        }
    }

    @Test
    void unicodeAndSqlLikeInputAreStoredAsData() {
        String title = "中文 🤖 '); DROP TABLE questions; --";
        var created = repository.insert(new CreateQuestionRequest(title, Topic.JAVA, Difficulty.MEDIUM, "回答含单引号 ' 和换行\n第二行"));
        assertEquals(created, repository.findById(created.id()).orElseThrow());
        assertEquals(title, created.title());
        assertNotNull(created.createdAt());
        assertTrue(repository.count(null) >= 1);
    }

    @Test
    void filteredPaginationUsesStableDescendingIds() {
        long before = repository.count(Topic.REDIS);
        var first = repository.insert(new CreateQuestionRequest("测试一", Topic.REDIS, Difficulty.EASY, "答案一"));
        var second = repository.insert(new CreateQuestionRequest("测试二", Topic.REDIS, Difficulty.HARD, "答案二"));
        assertEquals(before + 2, repository.count(Topic.REDIS));
        assertEquals(second.id(), repository.findPage(Topic.REDIS, 1, 0).getFirst().id());
        assertEquals(first.id(), repository.findPage(Topic.REDIS, 1, 1).getFirst().id());
        assertTrue(repository.findPage(null, 10, 214748364600L).isEmpty());
    }

    @Test
    void databaseCheckConstraintRejectsInvalidTopic() {
        var exception = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO questions (title, topic, difficulty, reference_answer)
                VALUES ('测试', 'INVALID', 'EASY', '答案')
                """));
        // MySQL CHECK 违反返回 HY000/3819，未必映射为 Spring 的完整性异常子类。
        var sqlException = assertInstanceOf(java.sql.SQLException.class, exception.getMostSpecificCause());
        assertEquals(3819, sqlException.getErrorCode());
    }
}
