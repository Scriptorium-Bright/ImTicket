-- KEYS: admitted ZSET, waiting ZSET, processing ZSET, retry ZSET, deadline ZSET, terminal ZSET,
--       active repair candidates ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, completedAtMs, retentionMs,
--       resultSchemaVersion, reservationId, totalPrice, orderUid, expiredTime
local function valid_type(key, expected)
    local actual = redis.call('TYPE', key)
    if type(actual) == 'table' then actual = actual['ok'] end
    return actual == 'none' or actual == expected
end
for index = 1, 7 do
    if not valid_type(KEYS[index], 'zset') then return 'KEY_TYPE_ERROR' end
end
if not valid_type(KEYS[8], 'hash') then return 'KEY_TYPE_ERROR' end
if redis.call('EXISTS', KEYS[8]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[8], 'status')
local stored_stream_id = redis.call('HGET', KEYS[8], 'streamId')
if status == 'SUCCEEDED' then
    if stored_stream_id == ARGV[2]
            and redis.call('HGET', KEYS[8], 'resultSchemaVersion') == ARGV[7]
            and redis.call('HGET', KEYS[8], 'reservationId') == ARGV[8]
            and redis.call('HGET', KEYS[8], 'totalPrice') == ARGV[9]
            and redis.call('HGET', KEYS[8], 'orderUid') == ARGV[10]
            and redis.call('HGET', KEYS[8], 'expiredTime') == ARGV[11] then
        return 'ALREADY_TERMINAL'
    end
    return 'INVALID_STATE'
end
if status ~= 'PROCESSING' then
    return 'INVALID_STATE'
end
if stored_stream_id ~= ARGV[2]
        or redis.call('HGET', KEYS[8], 'ownerToken') ~= ARGV[3] then
    return 'PAYLOAD_MISMATCH'
end
if redis.call('HGET', KEYS[8], 'workerId') ~= ARGV[4] then
    return 'OWNER_MISMATCH'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[1])
redis.call('ZADD', KEYS[6], ARGV[5], ARGV[1])
redis.call('ZADD', KEYS[7], 'GT', tonumber(ARGV[5]) + tonumber(ARGV[6]),
        redis.call('HGET', KEYS[8], 'performanceTimeId'))
redis.call('HSET', KEYS[8],
        'status', 'SUCCEEDED',
        'completedAt', ARGV[5],
        'resultSchemaVersion', ARGV[7],
        'reservationId', ARGV[8],
        'totalPrice', ARGV[9],
        'orderUid', ARGV[10],
        'expiredTime', ARGV[11])
redis.call('HDEL', KEYS[8], 'workerId', 'claimedAt', 'workerLeaseUntil', 'failureSchemaVersion', 'errorCode')
redis.call('PEXPIRE', KEYS[8], ARGV[6])
return 'COMPLETED'
