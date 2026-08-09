-- KEYS: admitted ZSET, waiting ZSET, deadline ZSET, ticket HASH
-- ARGV: ticketId, nowMs, resultRetentionMs
if redis.call('EXISTS', KEYS[4]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[4], 'status')
if status ~= 'WAITING' and status ~= 'RETRY_WAIT' then
    return 'NOT_EXPIRABLE'
end

local deadline_at = tonumber(redis.call('HGET', KEYS[4], 'deadlineAt'))
if not deadline_at then
    return 'CORRUPT'
end
if deadline_at > tonumber(ARGV[2]) then
    return 'NOT_DUE'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('HSET', KEYS[4], 'status', 'EXPIRED', 'expiredAt', ARGV[2])
redis.call('PEXPIRE', KEYS[4], ARGV[3])
return 'EXPIRED'
