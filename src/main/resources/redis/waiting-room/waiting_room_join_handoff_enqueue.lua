-- KEYS: handoff stream, request hash, handoff owner, sequence, waiting, deadline, ticket hash, ticket owner
-- ARGV: requestId, ticketId, performanceTimeId, memberId, enqueuedAtMs, waitingDeadlineMs, retentionMs, maxWaitingTickets
local existingRequestId = redis.call('GET', KEYS[3])
if existingRequestId then
    return 'EXISTING|' .. existingRequestId
end

local ticketId = ARGV[2]
local existingTicketId = redis.call('GET', KEYS[8])
local sequence = '0'

if existingTicketId then
    ticketId = existingTicketId
else
    if tonumber(redis.call('ZCARD', KEYS[5])) >= tonumber(ARGV[8]) then
        return 'QUEUE_FULL'
    end

    sequence = tostring(redis.call('INCR', KEYS[4]))
    redis.call('HSET', KEYS[7],
        'ticketId', ticketId,
        'memberId', ARGV[4],
        'performanceTimeId', ARGV[3],
        'status', 'WAITING',
        'sequence', sequence,
        'enqueuedAt', ARGV[5],
        'waitingDeadline', ARGV[6]
    )
    redis.call('ZADD', KEYS[5], tonumber(sequence), ticketId)
    redis.call('ZADD', KEYS[6], tonumber(ARGV[6]), ticketId)
    redis.call('SET', KEYS[8], ticketId)
end

redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[7])
redis.call('HSET', KEYS[2],
    'requestId', ARGV[1],
    'ticketId', ticketId,
    'performanceTimeId', ARGV[3],
    'memberId', ARGV[4],
    'enqueuedAt', ARGV[5],
    'status', 'QUEUED',
    'retryable', 'false'
)
redis.call('PEXPIRE', KEYS[2], ARGV[7])

local streamRecordId = redis.call('XADD', KEYS[1], '*',
    'requestId', ARGV[1],
    'ticketId', ticketId,
    'performanceTimeId', ARGV[3],
    'memberId', ARGV[4],
    'enqueuedAt', ARGV[5],
    'waitingDeadline', ARGV[6]
)

return 'CREATED|' .. ARGV[1] .. '|' .. ticketId .. '|' .. streamRecordId .. '|' .. sequence
