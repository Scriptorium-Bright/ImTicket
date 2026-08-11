-- KEYS: idempotency mapping HASH, ENQUEUING index ZSET, ticket HASH
-- ARGV: staleBeforeMs, repairedAtMs, retentionMs
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('ZREM', KEYS[2], KEYS[1])
    return 'ORPHAN_INDEX'
end
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'QUEUED' then
    redis.call('ZREM', KEYS[2], KEYS[1])
    return 'ALREADY_QUEUED'
end
if state ~= 'ENQUEUING' then return 'MISMATCH' end
local updatedAt = tonumber(redis.call('HGET', KEYS[1], 'updatedAt') or '0')
if updatedAt > tonumber(ARGV[1]) then return 'NOT_DUE' end

if redis.call('EXISTS', KEYS[3]) == 0 then
    redis.call('DEL', KEYS[1])
    redis.call('ZREM', KEYS[2], KEYS[1])
    return 'RELEASED'
end
if redis.call('HGET', KEYS[3], 'ticketId') ~= redis.call('HGET', KEYS[1], 'ticketId')
        or redis.call('HGET', KEYS[3], 'performanceTimeId') ~= redis.call('HGET', KEYS[1], 'performanceTimeId')
        or redis.call('HGET', KEYS[3], 'ownerToken') ~= redis.call('HGET', KEYS[1], 'ownerToken')
        or redis.call('HGET', KEYS[3], 'requestHash') ~= redis.call('HGET', KEYS[1], 'requestHash') then
    return 'MISMATCH'
end

redis.call('HSET', KEYS[1], 'state', 'QUEUED', 'updatedAt', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('ZREM', KEYS[2], KEYS[1])
return 'REPAIRED'
