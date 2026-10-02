package com.example.interviewagent.repository;

import com.example.interviewagent.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcInterviewRepository implements InterviewRepository {
    private static final RowMapper<InterviewSession> SESSION = (rs, n) -> new InterviewSession(
            rs.getLong("id"), rs.getString("topic") == null ? null : Topic.valueOf(rs.getString("topic")),
            rs.getInt("question_count"), InterviewStatus.valueOf(rs.getString("status")),
            time(rs, "created_at"), time(rs, "completed_at"));
    private static final RowMapper<InterviewTurn> TURN = (rs, n) -> new InterviewTurn(
            rs.getLong("id"), rs.getLong("session_id"), rs.getInt("position"), rs.getLong("question_id"),
            rs.getString("title"), Topic.valueOf(rs.getString("topic")),
            Difficulty.valueOf(rs.getString("difficulty")), rs.getString("reference_answer"),
            rs.getString("answer"), rs.getString("feedback"), rs.getString("feedback_mode"), time(rs, "answered_at"));
    private final JdbcTemplate jdbc;

    public JdbcInterviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    @Override
    public InterviewSession create(Topic topic, List<Question> questions) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO interview_sessions (topic, question_count) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, topic == null ? null : topic.name());
            statement.setInt(2, questions.size());
            return statement;
        }, keys);
        var key = keys.getKey();
        if (key == null) throw new IllegalStateException("未返回面试主键");
        long id = key.longValue();
        for (int i = 0; i < questions.size(); i++) {
            var question = questions.get(i);
            // 保存快照，而非查询历史时再 JOIN 最新题库，防止题目修改影响复盘。
            jdbc.update("""
                    INSERT INTO interview_turns
                    (session_id, position, question_id, title, topic, difficulty, reference_answer)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, i + 1, question.id(), question.title(), question.topic().name(),
                    question.difficulty().name(), question.referenceAnswer());
        }
        return find(id).orElseThrow();
    }

    @Override
    public Optional<InterviewSession> find(long id) {
        return jdbc.query("SELECT * FROM interview_sessions WHERE id = ?", SESSION, id).stream().findFirst();
    }

    @Override
    public Optional<InterviewSession> findForUpdate(long id) {
        // 必须在事务内调用。按会话行加锁：两个标签页同时提交时，后一个等待前一个提交。
        return jdbc.query("SELECT * FROM interview_sessions WHERE id = ? FOR UPDATE", SESSION, id)
                .stream().findFirst();
    }

    @Override
    public List<InterviewTurn> turns(long sessionId) {
        return jdbc.query("SELECT * FROM interview_turns WHERE session_id = ? ORDER BY position", TURN, sessionId);
    }

    @Override
    public void saveAnswer(long turnId, String answer, String feedback, String mode) {
        int updated = jdbc.update("""
                UPDATE interview_turns SET answer = ?, feedback = ?, feedback_mode = ?, answered_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND answered_at IS NULL
                """, answer, feedback, mode, turnId);
        if (updated != 1) throw new IllegalStateException("回答状态发生变化");
    }

    @Override
    public void complete(long sessionId) {
        jdbc.update("UPDATE interview_sessions SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP(6) WHERE id = ?", sessionId);
    }

    @Override
    public List<InterviewSession> findPage(int limit, long offset) {
        return jdbc.query("SELECT * FROM interview_sessions ORDER BY id DESC LIMIT ? OFFSET ?", SESSION, limit, offset);
    }

    @Override
    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_sessions", Long.class);
    }
}
