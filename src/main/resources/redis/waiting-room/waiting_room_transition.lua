-- KEYS: waiting, active, deadline, ticket hash, owner mapping
-- ARGV: ticketId, memberId, action, nowMs, retentionMs
if redis.call('EXISTS', KEYS[4]) == 0 then
    return 'MISSING'
end

local current = redis.call('HGET', KEYS[4], 'status')
local owner = redis.call('HGET', KEYS[4], 'memberId')
if ARGV[3] ~= 'EXPIRE' and owner ~= ARGV[2] then
    return 'OWNER_MISMATCH'
end

local target = nil
if ARGV[3] == 'CANCEL' then
    if current ~= 'WAITING' and current ~= 'ADMITTED' then
        return 'STATE_MISMATCH'
    end
    target = 'CANCELED'
elseif ARGV[3] == 'COMPLETE' then
    if current ~= 'ADMITTED' then
        return 'STATE_MISMATCH'
    end
    target = 'COMPLETED'
elseif ARGV[3] == 'EXPIRE' then
    if current == 'WAITING' then
        local deadline = tonumber(redis.call('HGET', KEYS[4], 'waitingDeadline') or '0')
        if deadline == 0 or deadline > tonumber(ARGV[4]) then
            return 'NOT_DUE'
        end
    elseif current == 'ADMITTED' then
        local lease = tonumber(redis.call('HGET', KEYS[4], 'entryExpiresAt') or '0')
        if lease == 0 or lease > tonumber(ARGV[4]) then
            return 'NOT_DUE'
        end
    else
        return 'STATE_MISMATCH'
    end
    target = 'EXPIRED'
else
    return 'ACTION_INVALID'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('HSET', KEYS[4], 'status', target, 'completedAt', ARGV[4])
redis.call('HDEL', KEYS[4], 'entryExpiresAt')
redis.call('DEL', KEYS[5])
redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[5]))
return 'TRANSITIONED|' .. target
