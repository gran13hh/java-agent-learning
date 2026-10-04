package com.example.interviewagent.ai;

import com.example.interviewagent.exception.*;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Redis 是共享快速拒绝层；MySQL 保留持久化校验，防止 Redis 丢数据或迁移期间超发。 */
@Component
@Primary
public class RedisRequestBudget implements RequestBudget {
    private static final DefaultRedisScript<Long> ACQUIRE = script("acquire.lua");
    private static final DefaultRedisScript<Long> BLOCK = script("block.lua");
    private final StringRedisTemplate redis;
    private final RequestBudget durable;
    public RedisRequestBudget(StringRedisTemplate redis, JdbcRequestBudget durable) { this.redis = redis; this.durable = durable; }
    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>(); script.setLocation(new ClassPathResource("redis/" + name)); script.setResultType(Long.class); return script;
    }
    private static String key(String provider) {
        if (provider == null || !provider.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("无效的限流桶");
        // 相同 hash tag 使两个 key 可在 Redis Cluster 的同一个槽执行 Lua（本项目使用单机）。
        return "interview:v5:rate:{" + provider + "}";
    }
    @Override public void acquire(String provider) {
        try {
            Long wait = redis.execute(ACQUIRE, List.of(key(provider) + ":permits", key(provider) + ":blocked"), UUID.randomUUID().toString());
            if (wait == null) throw new IllegalStateException();
            if (wait > 0) throw new AiRateLimitException((wait + 999) / 1000);
        } catch (AiRateLimitException limited) { throw limited; }
        catch (RuntimeException unavailable) { throw new AiCallException("Redis 限流服务不可用，已暂停外部模型请求；请启动 Redis 后手动重试"); }
        // 不做跨库回滚：MySQL 拒绝或异常时 Redis 名额不退，宁可少发，不能超发。
        durable.acquire(provider);
    }
    @Override public void block(String provider, Duration delay) {
        durable.block(provider, delay); // 先保留上游冷却期，Redis 重启后仍受保护。
        try { redis.execute(BLOCK, List.of(key(provider) + ":blocked"), Long.toString(Math.max(1000, delay.toMillis()))); }
        catch (RuntimeException unavailable) { throw new AiCallException("Redis 限流服务不可用；上游冷却期已保存在数据库"); }
    }
}
