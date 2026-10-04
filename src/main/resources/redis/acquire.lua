-- 用 Redis 时钟 + Lua 原子地清理、计数、预留；不同实例不能同时花掉最后一个名额。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local blocked = redis.call('PTTL', KEYS[2])
if blocked > 0 then return blocked end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
if redis.call('ZCARD', KEYS[1]) >= 5 then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return math.max(1, tonumber(oldest[2]) + 60000 - now)
end
redis.call('ZADD', KEYS[1], now, ARGV[1])
redis.call('PEXPIRE', KEYS[1], 61000)
return 0
