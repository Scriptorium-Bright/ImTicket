-- KEYS: processing ZSET, retry ZSET, admitted ZSET, deadline ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, failedAtMs, dueAtMs,
--       maxAttempts, errorCode, exhaustedCode, retentionMs
if redis.call('EXISTS', KEYS[5]) == 0 then return 'MISSING' end
local status = redis.call('HGET', KEYS[5], 'status')
if status == 'SUCCEEDED' or status == 'FAILED_FINAL' then return 'ALREADY_TERMINAL' end
if status ~= 'PROCESSING' then return 'STATE_MISMATCH' end
if redis.call('HGET', KEYS[5], 'streamId') ~= ARGV[2]
        or redis.call('HGET', KEYS[5], 'ownerToken') ~= ARGV[3]
        or redis.call('HGET', KEYS[5], 'workerId') ~= ARGV[4] then
    return 'OWNER_MISMATCH'
end

local attempts = tonumber(redis.call('HGET', KEYS[5], 'retryCount') or '0') + 1
redis.call('ZREM', KEYS[1], ARGV[1])
if attempts >= tonumber(ARGV[7]) then
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    redis.call('ZREM', KEYS[4], ARGV[1])
    redis.call('HSET', KEYS[5], 'status', 'FAILED_FINAL', 'retryCount', attempts,
            'errorCode', ARGV[9], 'completedAt', ARGV[5])
    redis.call('HDEL', KEYS[5], 'workerId', 'claimedAt', 'workerLeaseUntil')
    redis.call('PEXPIRE', KEYS[5], ARGV[10])
    return 'EXHAUSTED'
end

redis.call('ZADD', KEYS[2], ARGV[6], ARGV[1])
redis.call('HSET', KEYS[5], 'status', 'RETRY_WAIT', 'retryCount', attempts,
        'lastRetryErrorCode', ARGV[8], 'retryDueAt', ARGV[6])
redis.call('HDEL', KEYS[5], 'workerId', 'claimedAt', 'workerLeaseUntil')
redis.call('PEXPIRE', KEYS[2], ARGV[10])
redis.call('PEXPIRE', KEYS[5], ARGV[10])
return 'SCHEDULED'
