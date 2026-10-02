package com.example.interviewagent.ai;

import com.example.interviewagent.exception.AiRateLimitException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

/** 滑动窗口：每站点在连续 60 秒内最多放行 5 次；失败、超时不退还额度。 */
@Component
public class JdbcRequestBudget implements RequestBudget {
    public static final int LIMIT = 5;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcRequestBudget(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        // 额度预留独立提交，不因外层业务失败而回滚，否则可能超发。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void acquire(String provider) {
        transaction.executeWithoutResult(status -> {
            var blocked = jdbc.query("SELECT blocked_until FROM ai_rate_buckets WHERE provider = ? FOR UPDATE",
                    (rs, n) -> rs.getTimestamp(1), provider);
            if (blocked.isEmpty()) throw new IllegalStateException("未初始化请求限流桶");
            // 等锁成功后单独读取数据库时钟，避免 JVM 时钟漂移和等待前的过期时间。
            LocalDateTime now = now();
            if (blocked.getFirst() != null && blocked.getFirst().toLocalDateTime().isAfter(now)) {
                throw new AiRateLimitException(secondsUntil(now, blocked.getFirst().toLocalDateTime()));
            }
            // 只需保留当前窗口，表的规模保持很小。
            jdbc.update("DELETE FROM ai_request_permits WHERE provider = ? AND reserved_at <= ?",
                    provider, Timestamp.valueOf(now.minusSeconds(60)));
            var times = jdbc.query("SELECT reserved_at FROM ai_request_permits WHERE provider = ? ORDER BY reserved_at",
                    (rs, n) -> rs.getTimestamp(1).toLocalDateTime(), provider);
            if (times.size() >= LIMIT) throw new AiRateLimitException(secondsUntil(now, times.getFirst().plusSeconds(60)));
            jdbc.update("INSERT INTO ai_request_permits(provider, reserved_at) VALUES (?, ?)", provider, Timestamp.valueOf(now));
        });
    }

    @Override
    public void block(String provider, Duration delay) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT provider FROM ai_rate_buckets WHERE provider = ? FOR UPDATE", String.class, provider);
            var until = Timestamp.valueOf(now().plusSeconds(Math.max(1, delay.toSeconds())));
            jdbc.update("UPDATE ai_rate_buckets SET blocked_until = GREATEST(COALESCE(blocked_until, ?), ?) WHERE provider = ?",
                    until, until, provider);
        });
    }

    private LocalDateTime now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toLocalDateTime();
    }

    private long secondsUntil(LocalDateTime now, LocalDateTime until) {
        return Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000);
    }
}
