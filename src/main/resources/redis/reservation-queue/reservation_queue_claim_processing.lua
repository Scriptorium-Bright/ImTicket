-- KEYS: waiting ZSET, processing ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, payloadSchemaVersion,
--       requestHash, idempotencyKeyHash, claimedAtMs, leaseUntilMs, retentionMs
local function type_name(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then
        return value['ok']
    end
    return value
end

local function has_expected_type(key, expected)
    local actual = type_name(key)
    return actual == 'none' or actual == expected
end

if not has_expected_type(KEYS[1], 'zset')
        or not has_expected_type(KEYS[2], 'zset')
        or not has_expected_type(KEYS[3], 'hash') then
    return 'KEY_TYPE_ERROR'
end
if redis.call('EXISTS', KEYS[3]) == 0 then
    return 'MISSING'
end

local status = redis.call('HGET', KEYS[3], 'status')
local stored_stream_id = redis.call('HGET', KEYS[3], 'streamId')
if status == 'PROCESSING'
        and stored_stream_id == ARGV[2]
        and redis.call('HGET', KEYS[3], 'workerId') == ARGV[4] then
    return 'ALREADY_OWNED'
end
if status ~= 'WAITING' then
    return 'NOT_WAITING'
end

if redis.call('HGET', KEYS[3], 'ticketId') ~= ARGV[1]
        or stored_stream_id ~= ARGV[2]
        or redis.call('HGET', KEYS[3], 'ownerToken') ~= ARGV[3]
        or redis.call('HGET', KEYS[3], 'payloadSchemaVersion') ~= ARGV[5]
        or redis.call('HGET', KEYS[3], 'requestHash') ~= ARGV[6]
        or redis.call('HGET', KEYS[3], 'idempotencyKeyHash') ~= ARGV[7] then
    return 'PAYLOAD_MISMATCH'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[9], ARGV[1])
redis.call('HSET', KEYS[3],
        'status', 'PROCESSING',
        'workerId', ARGV[4],
        'claimedAt', ARGV[8],
        'workerLeaseUntil', ARGV[9])
redis.call('PEXPIRE', KEYS[2], ARGV[10])
redis.call('PEXPIRE', KEYS[3], ARGV[10])
return 'CLAIMED'
