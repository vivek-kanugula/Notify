-- Redis Lua token bucket rate limiter
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2]) -- tokens per second
local now = tonumber(ARGV[3]) -- current epoch seconds
local requested = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5]) -- idle ttl in seconds

local data = redis.call('HMGET', key, 'tokens', 'last_updated')
local tokens = tonumber(data[1])
local last_updated = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    last_updated = now
else
    local elapsed = math.max(0, now - last_updated)
    tokens = math.min(capacity, tokens + (elapsed * refill_rate))
    last_updated = now
end

local allowed = 0
local remaining = math.floor(tokens)
if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    remaining = math.floor(tokens)
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
    redis.call('EXPIRE', key, ttl)
else
    allowed = 0
    redis.call('EXPIRE', key, ttl)
end

local reset_seconds = math.ceil((capacity - tokens) / (refill_rate > 0 and refill_rate or 1))

local retry_after = 0
if allowed == 0 then
    local needed = requested - tokens
    retry_after = math.max(1, math.ceil(needed / (refill_rate > 0 and refill_rate or 1)))
end

return { allowed, capacity, remaining, reset_seconds, retry_after }
