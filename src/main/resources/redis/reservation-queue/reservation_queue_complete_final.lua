-- KEYS: admitted ZSET, waiting ZSET, processing ZSET, retry ZSET, deadline ZSET, terminal ZSET,
--       active repair candidates ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, completedAtMs, retentionMs,
--       failureSchemaVersion, errorCode
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
if status == 'FAILED_FINAL' then
    if stored_stream_id == ARGV[2]
            and redis.call('HGET', KEYS[8], 'failureSchemaVersion') == ARGV[7]
            and redis.call('HGET', KEYS[8], 'errorCode') == ARGV[8] then
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
        'status', 'FAILED_FINAL',
        'completedAt', ARGV[5],
        'failureSchemaVersion', ARGV[7],
        'errorCode', ARGV[8])
redis.call('HDEL', KEYS[8], 'workerId', 'claimedAt', 'workerLeaseUntil',
        'resultSchemaVersion', 'reservationId', 'totalPrice', 'orderUid', 'expiredTime')
redis.call('PEXPIRE', KEYS[8], ARGV[6])
return 'COMPLETED'
