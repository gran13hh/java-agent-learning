package com.example.interviewagent.service;

import com.example.interviewagent.domain.InterviewTurn;
import com.example.interviewagent.exception.*;
import com.example.interviewagent.repository.InterviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 与外部模型调用分成两个 Spring Bean，确保短事务方法确实经过代理。 */
@Service
public class FeedbackTransactions {
    private final JdbcTemplate jdbc;
    private final InterviewRepository interviews;
    public FeedbackTransactions(JdbcTemplate jdbc, InterviewRepository interviews) { this.jdbc = jdbc; this.interviews = interviews; }

    public record Claim(long sessionId, InterviewTurn turn, String token) {}

    @Transactional
    public Claim claim(long sessionId, long turnId) {
        if (sessionId < 1 || turnId < 1) throw new InvalidRequestException("面试和题号必须为正数");
        interviews.findForUpdate(sessionId).orElseThrow(() -> new InterviewNotFoundException(sessionId));
        var turn = interviews.turns(sessionId).stream().filter(t -> t.id() == turnId).findFirst()
                .orElseThrow(() -> new InvalidRequestException("该题不属于本场面试"));
        if (!turn.answered()) throw new InterviewConflictException("请先提交回答，再生成反馈");
        if ("AI".equals(turn.feedbackMode()) || "MOCK".equals(turn.feedbackMode())) return null;
        var active = jdbc.query("""
                SELECT state = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6) AS active
                FROM ai_feedback_tasks WHERE turn_id = ?
                """, (rs, n) -> rs.getBoolean(1), turnId);
        if (!active.isEmpty() && active.getFirst()) throw new InterviewConflictException("反馈正在生成，请稍后刷新；若进程中断，最多两分钟后可重试");
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ai_feedback_tasks(turn_id, token, state, lease_until)
                VALUES (?, ?, 'RUNNING', TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(6)))
                ON DUPLICATE KEY UPDATE token = ?, state = 'RUNNING',
                    lease_until = TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(6)), updated_at = CURRENT_TIMESTAMP(6)
                """, turnId, token, token);
        jdbc.update("UPDATE interview_turns SET feedback_mode = 'PROCESSING', feedback = 'AI 反馈正在生成，回答已保存。' WHERE id = ?", turnId);
        return new Claim(sessionId, turn, token);
    }

    @Transactional
    public void finish(Claim claim, String feedback, String mode) {
        // 与答题事务采用相同锁顺序，过期任务的晚到结果不会覆盖新任务。
        interviews.findForUpdate(claim.sessionId()).orElseThrow();
        int changed = jdbc.update("""
                UPDATE ai_feedback_tasks SET state = ?, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE turn_id = ? AND token = ? AND state = 'RUNNING'
                """, "AI".equals(mode) ? "SUCCEEDED" : "FAILED", claim.turn().id(), claim.token());
        if (changed == 1) jdbc.update("UPDATE interview_turns SET feedback = ?, feedback_mode = ? WHERE id = ?",
                feedback, mode, claim.turn().id());
    }
}
