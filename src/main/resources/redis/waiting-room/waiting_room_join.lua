-- KEYS: sequence, waiting, deadline, ticket hash, owner mapping
-- ARGV: ticketId, memberId, performanceTimeId, enqueuedAtMs, waitingDeadlineMs, retentionMs
local existing = redis.call('GET', KEYS[5])
if existing then
    return 'EXISTING|' .. existing
end

if redis.call('EXISTS', KEYS[4]) == 1 then
    return 'TICKET_EXISTS'
end

local sequence = redis.call('INCR', KEYS[1])
redis.call('HSET', KEYS[4],
        'ticketId', ARGV[1],
        'memberId', ARGV[2],
        'performanceTimeId', ARGV[3],
        'status', 'WAITING',
        'sequence', tostring(sequence),
        'enqueuedAt', ARGV[4],
        'waitingDeadline', ARGV[5])
redis.call('ZADD', KEYS[2], sequence, ARGV[1])
redis.call('ZADD', KEYS[3], tonumber(ARGV[5]), ARGV[1])
redis.call('SET', KEYS[5], ARGV[1])

for index = 1, 5 do
    redis.call('PEXPIRE', KEYS[index], tonumber(ARGV[6]))
end

return 'CREATED|' .. ARGV[1] .. '|' .. tostring(sequence)
