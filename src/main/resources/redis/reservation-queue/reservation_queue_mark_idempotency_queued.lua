-- KEYS[1]: owner and idempotency key scoped mapping
-- ARGV: ownerToken, ticketId, nowMs, retentionMs
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'MISSING'
end
if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[1] then
    return 'OWNER_MISMATCH'
end
if redis.call('HGET', KEYS[1], 'ticketId') ~= ARGV[2] then
    return 'TICKET_MISMATCH'
end

local state = redis.call('HGET', KEYS[1], 'state')
if state == 'QUEUED' then
    return 'ALREADY_QUEUED'
end
if state ~= 'ENQUEUING' then
    return 'INVALID_STATE'
end

redis.call('HSET', KEYS[1], 'state', 'QUEUED', 'updatedAt', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 'MARKED'
