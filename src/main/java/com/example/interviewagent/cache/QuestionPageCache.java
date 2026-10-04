package com.example.interviewagent.cache;

import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.PageResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** 只缓存首页常见规格，限制 key 数量；空列表也缓存，不缓存用户输入的无限种组合。 */
@Component
public class QuestionPageCache {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public QuestionPageCache(StringRedisTemplate redis, JdbcTemplate jdbc) { this.redis = redis; this.jdbc = jdbc; }
    public PageResponse<Question> getOrLoad(Topic topic, int page, int size, Supplier<PageResponse<Question>> load) {
        if (page != 1 || (size != 5 && size != 20)) return load.get();
        // 版本与写题在同一 MySQL 事务内提交。晚到的旧查询只能回填旧版本 key，不能污染新版本。
        // 每次命中仍有一次主键版本查询；省掉的是列表与 count 查询，并非完全不访问数据库。
        Long generation = jdbc.queryForObject("SELECT version FROM cache_versions WHERE name = 'questions'", Long.class);
        String key = "interview:v5:questions:" + generation + ":" + (topic == null ? "ALL" : topic.name()) + ":" + size;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, new TypeReference<PageResponse<Question>>() {});
        } catch (RuntimeException ignored) { return load.get(); } // 连接故障/坏缓存回源，不影响题库可用性。
        var value = load.get();
        try { redis.opsForValue().set(key, json.writeValueAsString(value), Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(16))); }
        catch (RuntimeException ignored) { /* 数据库结果仍可正常返回；缓存不是事实来源。 */ }
        return value;
    }
}
