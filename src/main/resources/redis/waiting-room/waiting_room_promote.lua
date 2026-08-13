-- KEYS: waiting, active, deadline, ticket hash
-- ARGV: ticketId, nowMs, entryExpiresAtMs, maxActiveSessions, retentionMs
local waitingScore = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not waitingScore then
    return 'MISSING'
end
if redis.call('EXISTS', KEYS[4]) == 0 then
    redis.call('ZREM', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    return 'MISSING'
end
if redis.call('HGET', KEYS[4], 'status') ~= 'WAITING' then
    redis.call('ZREM', KEYS[1], ARGV[1])
    return 'STATE_MISMATCH'
end
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then
    return 'FULL'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), ARGV[1])
redis.call('HSET', KEYS[4],
        'status', 'ADMITTED',
        'entryExpiresAt', ARGV[3])

redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[5]))
redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[5]))
return 'PROMOTED|' .. ARGV[1]
