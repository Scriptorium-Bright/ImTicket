-- KEYS: admitted ZSET, waiting ZSET, deadline ZSET, sequence STRING, ticket HASH, stream
-- ARGV: ticketId, performanceTimeId, ownerHash, ownerToken, payloadSchemaVersion,
--       memberId, idempotencyKey, idempotencyKeyHash, requestHash, seatIds,
--       enqueuedAtMs, deadlineAtMs, maxDepth, retentionMs
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
        or not has_expected_type(KEYS[3], 'zset')
        or not has_expected_type(KEYS[4], 'string')
        or not has_expected_type(KEYS[5], 'hash')
        or not has_expected_type(KEYS[6], 'stream') then
    return 'KEY_TYPE_ERROR'
end
if redis.call('EXISTS', KEYS[5]) == 1 then
    return 'TICKET_EXISTS'
end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[13]) then
    return 'FULL'
end

local sequence = redis.call('INCR', KEYS[4])
local stream_id = redis.call('XADD', KEYS[6], '*',
        'ticketId', ARGV[1],
        'performanceTimeId', ARGV[2],
        'ownerHash', ARGV[3],
        'ownerToken', ARGV[4],
        'payloadSchemaVersion', ARGV[5],
        'memberId', ARGV[6],
        'idempotencyKey', ARGV[7],
        'idempotencyKeyHash', ARGV[8],
        'requestHash', ARGV[9],
        'seatIds', ARGV[10],
        'sequence', tostring(sequence),
        'enqueuedAt', ARGV[11])

redis.call('HSET', KEYS[5],
        'ticketId', ARGV[1],
        'performanceTimeId', ARGV[2],
        'ownerHash', ARGV[3],
        'ownerToken', ARGV[4],
        'payloadSchemaVersion', ARGV[5],
        'memberId', ARGV[6],
        'idempotencyKey', ARGV[7],
        'idempotencyKeyHash', ARGV[8],
        'requestHash', ARGV[9],
        'seatIds', ARGV[10],
        'status', 'WAITING',
        'sequence', tostring(sequence),
        'streamId', stream_id,
        'enqueuedAt', ARGV[11],
        'deadlineAt', ARGV[12])
redis.call('ZADD', KEYS[1], sequence, ARGV[1])
redis.call('ZADD', KEYS[2], sequence, ARGV[1])
redis.call('ZADD', KEYS[3], ARGV[12], ARGV[1])

for index = 1, 6 do
    redis.call('PEXPIRE', KEYS[index], ARGV[14])
end

return 'ACCEPTED|' .. tostring(sequence) .. '|' .. stream_id
