-- KEYS: retry ZSET, waiting ZSET, stream, ticket HASH
-- ARGV: ticketId, nowMs, retentionMs
if redis.call('EXISTS', KEYS[4]) == 0 then
    redis.call('ZREM', KEYS[1], ARGV[1])
    return 'MISSING'
end
if redis.call('HGET', KEYS[4], 'status') ~= 'RETRY_WAIT' then
    redis.call('ZREM', KEYS[1], ARGV[1])
    return 'STATE_MISMATCH'
end
local due = tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0')
if due == 0 or due > tonumber(ARGV[2]) then return 'NOT_DUE' end

local streamId = redis.call('XADD', KEYS[3], '*',
        'ticketId', redis.call('HGET', KEYS[4], 'ticketId'),
        'performanceTimeId', redis.call('HGET', KEYS[4], 'performanceTimeId'),
        'ownerHash', redis.call('HGET', KEYS[4], 'ownerHash'),
        'ownerToken', redis.call('HGET', KEYS[4], 'ownerToken'),
        'payloadSchemaVersion', redis.call('HGET', KEYS[4], 'payloadSchemaVersion'),
        'memberId', redis.call('HGET', KEYS[4], 'memberId'),
        'idempotencyKey', redis.call('HGET', KEYS[4], 'idempotencyKey'),
        'idempotencyKeyHash', redis.call('HGET', KEYS[4], 'idempotencyKeyHash'),
        'requestHash', redis.call('HGET', KEYS[4], 'requestHash'),
        'seatIds', redis.call('HGET', KEYS[4], 'seatIds'),
        'sequence', redis.call('HGET', KEYS[4], 'sequence'),
        'enqueuedAt', redis.call('HGET', KEYS[4], 'enqueuedAt'))
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[2], redis.call('HGET', KEYS[4], 'sequence'), ARGV[1])
redis.call('HSET', KEYS[4], 'status', 'WAITING', 'streamId', streamId)
redis.call('HDEL', KEYS[4], 'retryDueAt')
redis.call('PEXPIRE', KEYS[2], ARGV[3])
redis.call('PEXPIRE', KEYS[3], ARGV[3])
redis.call('PEXPIRE', KEYS[4], ARGV[3])
return 'PROMOTED'
