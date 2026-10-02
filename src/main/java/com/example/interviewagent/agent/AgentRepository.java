package com.example.interviewagent.agent;

import com.example.interviewagent.exception.InterviewConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** 仅在写入本地状态时持有短事务；GET 不执行模型或恢复任务。 */
@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;
    public AgentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Run(long id, String requestId, String prompt, Long sessionId, String state, String result,
                      String message, int modelCalls, int toolCalls, long retryAfterSeconds, LocalDateTime createdAt, LocalDateTime completedAt) {}
    public record Step(int position, String kind, String name, String state, String input, String output, LocalDateTime createdAt, LocalDateTime completedAt) {}
    public record Detail(Run run, List<Step> steps) {}
    public record Claim(Run run, String token) {}
    private static final String SELECT = "SELECT *, state = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AS expired FROM agent_runs";
    private Run map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        boolean expired = rs.getBoolean("expired");
        var completed = rs.getTimestamp("completed_at");
        return new Run(rs.getLong("id"), rs.getString("request_id"), rs.getString("prompt"), rs.getObject("session_id", Long.class),
                expired ? "INTERRUPTED" : rs.getString("state"), rs.getString("result"),
                expired ? "上次执行已中断或超出租约；不会自动重发，可新建一次尝试" : rs.getString("message"),
                rs.getInt("model_calls"), rs.getInt("tool_calls"), rs.getLong("retry_after_seconds"),
                rs.getTimestamp("created_at").toLocalDateTime(), completed == null ? null : completed.toLocalDateTime());
    }
    public Run get(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 运行不存在"));
    }
    public List<Run> list() { return jdbc.query(SELECT + " ORDER BY id DESC LIMIT 20", this::map); }
    public Detail detail(long id) {
        var run = get(id);
        var steps = jdbc.query("SELECT * FROM agent_steps WHERE run_id = ? ORDER BY position", (rs, n) -> {
            var completed = rs.getTimestamp("completed_at");
            return new Step(rs.getInt("position"), rs.getString("kind"), rs.getString("name"),
                    run.state().equals("INTERRUPTED") && rs.getString("state").equals("STARTED") ? "FAILED" : rs.getString("state"),
                    rs.getString("input_json"), rs.getString("output_json"), rs.getTimestamp("created_at").toLocalDateTime(),
                    completed == null ? null : completed.toLocalDateTime());
        }, id);
        return new Detail(run, steps);
    }
    @Transactional
    public Run create(String requestId, String prompt, Long sessionId) {
        jdbc.update("INSERT INTO agent_runs(request_id,prompt,session_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE request_id = ?", requestId, prompt, sessionId, requestId);
        var run = jdbc.query(SELECT + " WHERE request_id = ?", this::map, requestId).getFirst();
        if (!run.prompt().equals(prompt) || !Objects.equals(run.sessionId(), sessionId))
            throw new InterviewConflictException("该请求编号已用于其他内容，请创建新的请求编号");
        return run;
    }
    @Transactional
    public Claim claim(long id) {
        jdbc.query("SELECT id FROM agent_runs WHERE id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), id);
        var run = get(id);
        if (run.state().equals("RUNNING")) throw new InterviewConflictException("本次运行正在执行，请读取进度，不要重复启动");
        if (!run.state().equals("PENDING")) return null;
        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE agent_runs SET state = 'RUNNING', token = ?, lease_until = TIMESTAMPADD(SECOND,150,CURRENT_TIMESTAMP(6)), message = '正在执行受控 Agent' WHERE id = ?", token, id);
        return new Claim(run, token);
    }
    private void lockActive(Claim claim) {
        var active = jdbc.query("SELECT id FROM agent_runs WHERE id = ? AND token = ? AND state = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6) FOR UPDATE",
                (rs, n) -> rs.getLong(1), claim.run().id(), claim.token());
        if (active.isEmpty()) throw new InterviewConflictException("Agent 执行租约已失效");
    }
    @Transactional
    public int startStep(Claim claim, String kind, String name, String input) {
        lockActive(claim);
        int position = jdbc.queryForObject("SELECT COUNT(*) FROM agent_steps WHERE run_id = ?", Integer.class, claim.run().id()) + 1;
        jdbc.update("INSERT INTO agent_steps(run_id,position,kind,name,input_json) VALUES (?,?,?,?,?)", claim.run().id(), position, kind, name, input);
        String column = kind.equals("MODEL") ? "model_calls" : "tool_calls"; // 只由本地常量选择，绝不接收模型生成的 SQL。
        jdbc.update("UPDATE agent_runs SET " + column + " = " + column + " + 1 WHERE id = ?", claim.run().id());
        return position;
    }
    @Transactional
    public void endStep(Claim claim, int step, String state, String output) {
        lockActive(claim);
        jdbc.update("UPDATE agent_steps SET state = ?, output_json = ?, completed_at = CURRENT_TIMESTAMP(6) WHERE run_id = ? AND position = ? AND state = 'STARTED'",
                state, output, claim.run().id(), step);
    }
    @Transactional
    public void finish(Claim claim, AgentEngine.Outcome outcome) {
        jdbc.update("""
                UPDATE agent_runs SET state = ?, result = ?, message = ?, model_calls = ?, tool_calls = ?, retry_after_seconds = ?,
                  lease_until = NULL, completed_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND token = ? AND state = 'RUNNING' AND lease_until > CURRENT_TIMESTAMP(6)
                """, outcome.state(), outcome.result(), outcome.message(), outcome.modelCalls(), outcome.toolCalls(), outcome.retryAfterSeconds(), claim.run().id(), claim.token());
    }
}
