-- KEYS: admitted ZSET, waiting ZSET, processing ZSET, deadline ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, completedAtMs, retentionMs,
--       failureSchemaVersion, errorCode
if redis.call('EXISTS', KEYS[5]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[5], 'status')
local stored_stream_id = redis.call('HGET', KEYS[5], 'streamId')
if status == 'FAILED_FINAL' then
    if stored_stream_id == ARGV[2]
            and redis.call('HGET', KEYS[5], 'failureSchemaVersion') == ARGV[7]
            and redis.call('HGET', KEYS[5], 'errorCode') == ARGV[8] then
        return 'ALREADY_TERMINAL'
    end
    return 'INVALID_STATE'
end
if status ~= 'PROCESSING' then
    return 'INVALID_STATE'
end
if stored_stream_id ~= ARGV[2]
        or redis.call('HGET', KEYS[5], 'ownerToken') ~= ARGV[3] then
    return 'PAYLOAD_MISMATCH'
end
if redis.call('HGET', KEYS[5], 'workerId') ~= ARGV[4] then
    return 'OWNER_MISMATCH'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('HSET', KEYS[5],
        'status', 'FAILED_FINAL',
        'completedAt', ARGV[5],
        'failureSchemaVersion', ARGV[7],
        'errorCode', ARGV[8])
redis.call('HDEL', KEYS[5], 'workerId', 'claimedAt', 'workerLeaseUntil',
        'resultSchemaVersion', 'reservationId', 'totalPrice', 'orderUid', 'expiredTime')
redis.call('PEXPIRE', KEYS[5], ARGV[6])
return 'COMPLETED'
