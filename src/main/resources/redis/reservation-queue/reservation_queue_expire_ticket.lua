-- KEYS: admitted ZSET, waiting ZSET, retry ZSET, deadline ZSET, terminal ZSET,
--       active repair candidates ZSET, ticket HASH
-- ARGV: ticketId, nowMs, resultRetentionMs
local function valid_type(key, expected)
    local actual = redis.call('TYPE', key)
    if type(actual) == 'table' then actual = actual['ok'] end
    return actual == 'none' or actual == expected
end
for index = 1, 6 do
    if not valid_type(KEYS[index], 'zset') then return 'KEY_TYPE_ERROR' end
end
if not valid_type(KEYS[7], 'hash') then return 'KEY_TYPE_ERROR' end
if redis.call('EXISTS', KEYS[7]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[7], 'status')
if status ~= 'WAITING' and status ~= 'RETRY_WAIT' then
    return 'NOT_EXPIRABLE'
end

local deadline_at = tonumber(redis.call('HGET', KEYS[7], 'deadlineAt'))
if not deadline_at then
    return 'CORRUPT'
end
if deadline_at > tonumber(ARGV[2]) then
    return 'NOT_DUE'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('ZADD', KEYS[5], ARGV[2], ARGV[1])
redis.call('ZADD', KEYS[6], 'GT', tonumber(ARGV[2]) + tonumber(ARGV[3]),
        redis.call('HGET', KEYS[7], 'performanceTimeId'))
redis.call('HSET', KEYS[7], 'status', 'EXPIRED', 'expiredAt', ARGV[2])
redis.call('PEXPIRE', KEYS[7], ARGV[3])
return 'EXPIRED'
