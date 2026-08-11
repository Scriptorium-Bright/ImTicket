-- KEYS: admitted ZSET, waiting ZSET, processing ZSET, deadline ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, completedAtMs, retentionMs,
--       resultSchemaVersion, reservationId, totalPrice, orderUid, expiredTime
if redis.call('EXISTS', KEYS[5]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[5], 'status')
local stored_stream_id = redis.call('HGET', KEYS[5], 'streamId')
if status == 'SUCCEEDED' then
    if stored_stream_id == ARGV[2]
            and redis.call('HGET', KEYS[5], 'resultSchemaVersion') == ARGV[7]
            and redis.call('HGET', KEYS[5], 'reservationId') == ARGV[8]
            and redis.call('HGET', KEYS[5], 'totalPrice') == ARGV[9]
            and redis.call('HGET', KEYS[5], 'orderUid') == ARGV[10]
            and redis.call('HGET', KEYS[5], 'expiredTime') == ARGV[11] then
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
        'status', 'SUCCEEDED',
        'completedAt', ARGV[5],
        'resultSchemaVersion', ARGV[7],
        'reservationId', ARGV[8],
        'totalPrice', ARGV[9],
        'orderUid', ARGV[10],
        'expiredTime', ARGV[11])
redis.call('HDEL', KEYS[5], 'workerId', 'claimedAt', 'workerLeaseUntil', 'failureSchemaVersion', 'errorCode')
redis.call('PEXPIRE', KEYS[5], ARGV[6])
return 'COMPLETED'
