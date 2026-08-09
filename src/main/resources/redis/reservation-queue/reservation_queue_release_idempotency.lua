-- KEYS[1]: owner and idempotency key scoped mapping
-- ARGV: ownerToken, ticketId
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'MISSING'
end
if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[1] then
    return 'OWNER_MISMATCH'
end
if redis.call('HGET', KEYS[1], 'ticketId') ~= ARGV[2] then
    return 'TICKET_MISMATCH'
end
if redis.call('HGET', KEYS[1], 'state') ~= 'ENQUEUING' then
    return 'NOT_RELEASABLE'
end

redis.call('DEL', KEYS[1])
return 'RELEASED'
