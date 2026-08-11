-- KEYS: owner and idempotency key scoped mapping, ENQUEUING index ZSET
-- ARGV: requestHash, ticketId, performanceTimeId, ownerToken, nowMs, retentionMs
local existing_request_hash = redis.call('HGET', KEYS[1], 'requestHash')
if existing_request_hash then
    if existing_request_hash ~= ARGV[1] then
        return 'CONFLICT'
    end

    local state = redis.call('HGET', KEYS[1], 'state')
    local ticket_id = redis.call('HGET', KEYS[1], 'ticketId')
    local performance_time_id = redis.call('HGET', KEYS[1], 'performanceTimeId')
    if not state or not ticket_id or not performance_time_id then
        return 'CORRUPT'
    end
    if state == 'ENQUEUING' then
        local indexed_at = redis.call('HGET', KEYS[1], 'updatedAt')
                or redis.call('HGET', KEYS[1], 'createdAt')
        if indexed_at then redis.call('ZADD', KEYS[2], indexed_at, KEYS[1]) end
    end
    return 'EXISTING|' .. state .. '|' .. ticket_id .. '|' .. performance_time_id
end

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'CORRUPT'
end

redis.call('HSET', KEYS[1],
        'state', 'ENQUEUING',
        'requestHash', ARGV[1],
        'ticketId', ARGV[2],
        'performanceTimeId', ARGV[3],
        'ownerToken', ARGV[4],
        'createdAt', ARGV[5],
        'updatedAt', ARGV[5])
redis.call('PEXPIRE', KEYS[1], ARGV[6])
redis.call('ZADD', KEYS[2], ARGV[5], KEYS[1])
return 'CREATED'
