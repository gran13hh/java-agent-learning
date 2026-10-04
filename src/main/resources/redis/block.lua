-- 后来的短 Retry-After 不能缩短已经存在的冷却期。
local delay = tonumber(ARGV[1])
if redis.call('PTTL', KEYS[1]) < delay then redis.call('SET', KEYS[1], 'blocked', 'PX', delay) end
return 1
