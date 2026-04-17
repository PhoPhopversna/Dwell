-- Rate Limiter Script
-- KEYS[1] : Redis key (identifier:path:method)
-- ARGV[1] : request limit (max allowed per window)
-- ARGV[2] : window duration in seconds

local key     = KEYS[1]
local limit   = tonumber(ARGV[1])
local window  = tonumber(ARGV[2])

local current = redis.call('INCR', key)

if current == 1 then
    redis.call('EXPIRE', key, window)
end

if current > limit then
    return 0  -- rate limit exceeded
else
    return 1  -- request allowed
end