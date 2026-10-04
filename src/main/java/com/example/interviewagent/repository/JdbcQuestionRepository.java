package com.example.interviewagent.repository;

import com.example.interviewagent.domain.Difficulty;
import com.example.interviewagent.domain.Question;
import com.example.interviewagent.domain.Topic;
import com.example.interviewagent.dto.CreateQuestionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcQuestionRepository implements QuestionRepository {
    private static final String COLUMNS = "id, title, topic, difficulty, reference_answer, created_at";
    private static final RowMapper<Question> ROW_MAPPER = (rs, rowNum) -> new Question(
            rs.getLong("id"), rs.getString("title"), Topic.valueOf(rs.getString("topic")),
            Difficulty.valueOf(rs.getString("difficulty")), rs.getString("reference_answer"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public JdbcQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Question insert(CreateQuestionRequest request) {
        var keys = new GeneratedKeyHolder();
        // ? 是绑定参数，不拼接用户输入：数据库将其作为数据，而不是 SQL 语法。
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO questions (title, topic, difficulty, reference_answer)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.title());
            statement.setString(2, request.topic().name());
            statement.setString(3, request.difficulty().name());
            statement.setString(4, request.referenceAnswer());
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("数据库未返回新增题目的主键");
        }
        jdbc.update("UPDATE cache_versions SET version = version + 1 WHERE name = 'questions'");
        // 读取数据库实际生成的 id 和时间；调用方的事务覆盖 INSERT 与 SELECT。
        return findById(id.longValue()).orElseThrow(() -> new IllegalStateException("新增题目读取失败"));
    }

    @Override
    public Optional<Question> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM questions WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<Question> findPage(Topic topic, int limit, long offset) {
        // 固定使用 id 排序，使分页顺序明确；大数据量的深分页优化留作后续练习。
        if (topic == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM questions ORDER BY id DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER, limit, offset);
        }
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM questions WHERE topic = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, topic.name(), limit, offset);
    }

    @Override
    public long count(Topic topic) {
        Long count = topic == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM questions", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE topic = ?", Long.class, topic.name());
        return count == null ? 0 : count;
    }
}
