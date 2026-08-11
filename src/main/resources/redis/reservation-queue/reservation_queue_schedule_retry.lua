-- KEYS: processing ZSET, retry ZSET, admitted ZSET, deadline ZSET, terminal ZSET,
--       active repair candidates ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, failedAtMs, dueAtMs,
--       maxAttempts, errorCode, exhaustedCode, retentionMs
local function valid_type(key, expected)
    local actual = redis.call('TYPE', key)
    if type(actual) == 'table' then actual = actual['ok'] end
    return actual == 'none' or actual == expected
end
for index = 1, 6 do
    if not valid_type(KEYS[index], 'zset') then return 'KEY_TYPE_ERROR' end
end
if not valid_type(KEYS[7], 'hash') then return 'KEY_TYPE_ERROR' end
if redis.call('EXISTS', KEYS[7]) == 0 then return 'MISSING' end
local status = redis.call('HGET', KEYS[7], 'status')
if status == 'SUCCEEDED' or status == 'FAILED_FINAL' then return 'ALREADY_TERMINAL' end
if status ~= 'PROCESSING' then return 'STATE_MISMATCH' end
if redis.call('HGET', KEYS[7], 'streamId') ~= ARGV[2]
        or redis.call('HGET', KEYS[7], 'ownerToken') ~= ARGV[3]
        or redis.call('HGET', KEYS[7], 'workerId') ~= ARGV[4] then
    return 'OWNER_MISMATCH'
end

local attempts = tonumber(redis.call('HGET', KEYS[7], 'retryCount') or '0') + 1
redis.call('ZREM', KEYS[1], ARGV[1])
if attempts >= tonumber(ARGV[7]) then
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    redis.call('ZREM', KEYS[4], ARGV[1])
    redis.call('ZADD', KEYS[5], ARGV[5], ARGV[1])
    redis.call('ZADD', KEYS[6], 'GT', tonumber(ARGV[5]) + tonumber(ARGV[10]),
            redis.call('HGET', KEYS[7], 'performanceTimeId'))
    redis.call('HSET', KEYS[7], 'status', 'FAILED_FINAL', 'retryCount', attempts,
            'errorCode', ARGV[9], 'completedAt', ARGV[5])
    redis.call('HDEL', KEYS[7], 'workerId', 'claimedAt', 'workerLeaseUntil')
    redis.call('PEXPIRE', KEYS[7], ARGV[10])
    return 'EXHAUSTED'
end

redis.call('ZADD', KEYS[2], ARGV[6], ARGV[1])
redis.call('HSET', KEYS[7], 'status', 'RETRY_WAIT', 'retryCount', attempts,
        'lastRetryErrorCode', ARGV[8], 'retryDueAt', ARGV[6])
redis.call('HDEL', KEYS[7], 'workerId', 'claimedAt', 'workerLeaseUntil')
redis.call('PEXPIRE', KEYS[2], ARGV[10])
redis.call('PEXPIRE', KEYS[7], ARGV[10])
return 'SCHEDULED'
