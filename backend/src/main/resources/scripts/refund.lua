-- Redis Lua token bucket rate limiter: refund token
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2]) -- tokens per second
local now = tonumber(ARGV[3]) -- current epoch seconds
local refund = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5]) -- idle ttl in seconds

local data = redis.call('HMGET', key, 'tokens', 'last_updated')
local tokens = tonumber(data[1])
local last_updated = tonumber(data[2])

if tokens ~= nil then
    local elapsed = math.max(0, now - (last_updated or now))
    tokens = math.min(capacity, tokens + (elapsed * refill_rate) + refund)
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', now)
    redis.call('EXPIRE', key, ttl)
end

return { tokens or capacity }
