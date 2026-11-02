-- Atomic IP Rate Limiter with TTL repair
-- KEYS[1]: rate limit key (e.g. rl:ip:<ip>:<route>)
-- ARGV[1]: window in seconds (e.g. 60)

local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])
local window = tonumber(ARGV[1])

if current == 1 or ttl == -1 then
    redis.call('EXPIRE', KEYS[1], window)
    ttl = window
end

return { current, ttl }
