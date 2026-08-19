-- KEYS: waiting, active, deadline, admission, ticket/owner key pairs
-- ARGV: nowMs, entryExpiresAtMs, maxActiveSessions, maxPerWindow,
--       windowId, retentionMs, candidateCount, candidate ticket IDs

local nowMs = tonumber(ARGV[1])
local entryExpiresAtMs = tonumber(ARGV[2])
local maxActiveSessions = tonumber(ARGV[3])
local maxPerWindow = tonumber(ARGV[4])
local windowId = ARGV[5]
local retentionMs = tonumber(ARGV[6])
local candidateCount = tonumber(ARGV[7])
local activeCount = tonumber(redis.call('ZCARD', KEYS[2]) or '0')
local currentWindow = redis.call('HGET', KEYS[4], 'window')
local windowCount = tonumber(redis.call('HGET', KEYS[4], 'count') or '0')
local results = {}

if currentWindow ~= windowId then
    windowCount = 0
    redis.call('HSET', KEYS[4], 'window', windowId, 'count', '0')
end

for index = 1, candidateCount do
    if activeCount >= maxActiveSessions or windowCount >= maxPerWindow then
        break
    end

    local ticketId = ARGV[7 + index]
    local ticketKeyIndex = 5 + ((index - 1) * 2)
    local ticketKey = KEYS[ticketKeyIndex]
    local ownerKey = KEYS[ticketKeyIndex + 1]
    local waitingScore = redis.call('ZSCORE', KEYS[1], ticketId)

    if waitingScore then
        if redis.call('EXISTS', ticketKey) == 0 then
            redis.call('ZREM', KEYS[1], ticketId)
            redis.call('ZREM', KEYS[2], ticketId)
            redis.call('ZREM', KEYS[3], ticketId)
        else
            local currentStatus = redis.call('HGET', ticketKey, 'status')
            if currentStatus ~= 'WAITING' then
                redis.call('ZREM', KEYS[1], ticketId)
                redis.call('ZREM', KEYS[3], ticketId)
            else
                local waitingDeadline = tonumber(redis.call('HGET', ticketKey, 'waitingDeadline') or '0')
                if waitingDeadline == 0 or waitingDeadline <= nowMs then
                    redis.call('ZREM', KEYS[1], ticketId)
                    redis.call('ZREM', KEYS[2], ticketId)
                    redis.call('ZREM', KEYS[3], ticketId)
                    if redis.call('GET', ownerKey) == ticketId then
                        redis.call('DEL', ownerKey)
                    end
                    redis.call('HSET', ticketKey, 'status', 'EXPIRED', 'completedAt', nowMs)
                    redis.call('HDEL', ticketKey, 'entryExpiresAt')
                    redis.call('PEXPIRE', ticketKey, retentionMs)
                    table.insert(results, 'EXPIRED|' .. ticketId)
                else
                    redis.call('ZREM', KEYS[1], ticketId)
                    redis.call('ZREM', KEYS[3], ticketId)
                    redis.call('ZADD', KEYS[2], entryExpiresAtMs, ticketId)
                    redis.call('HSET', ticketKey,
                        'status', 'ADMITTED',
                        'entryExpiresAt', entryExpiresAtMs)
                    windowCount = windowCount + 1
                    activeCount = activeCount + 1
                    redis.call('HSET', KEYS[4], 'window', windowId, 'count', tostring(windowCount))
                    redis.call('PEXPIRE', KEYS[2], retentionMs)
                    redis.call('PEXPIRE', ticketKey, retentionMs)
                    redis.call('PEXPIRE', KEYS[4], retentionMs)
                    table.insert(results, 'PROMOTED|' .. ticketId)
                end
            end
        end
    end
end

return table.concat(results, '\n')
