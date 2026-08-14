-- KEYS: waiting, active, deadline, ticket hash, owner mapping, admission hash
-- ARGV: ticketId, nowMs, entryExpiresAtMs, maxActiveSessions, maxPerWindow, windowId, retentionMs
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

local waitingDeadline = tonumber(redis.call('HGET', KEYS[4], 'waitingDeadline') or '0')
if waitingDeadline == 0 or waitingDeadline <= tonumber(ARGV[2]) then
    redis.call('ZREM', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    if redis.call('GET', KEYS[5]) == ARGV[1] then
        redis.call('DEL', KEYS[5])
    end
    redis.call('HSET', KEYS[4], 'status', 'EXPIRED', 'completedAt', ARGV[2])
    redis.call('HDEL', KEYS[4], 'entryExpiresAt')
    redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[7]))
    return 'EXPIRED|' .. ARGV[1]
end

if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then
    return 'FULL'
end

local currentWindow = redis.call('HGET', KEYS[6], 'window')
local windowCount = tonumber(redis.call('HGET', KEYS[6], 'count') or '0')
if currentWindow ~= ARGV[6] then
    windowCount = 0
    redis.call('HSET', KEYS[6], 'window', ARGV[6], 'count', '0')
end
if windowCount >= tonumber(ARGV[5]) then
    return 'RATE_LIMIT'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), ARGV[1])
redis.call('HSET', KEYS[4],
        'status', 'ADMITTED',
        'entryExpiresAt', ARGV[3])
redis.call('HSET', KEYS[6], 'window', ARGV[6], 'count', tostring(windowCount + 1))

redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[7]))
redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[7]))
redis.call('PEXPIRE', KEYS[6], tonumber(ARGV[7]))
return 'PROMOTED|' .. ARGV[1]
