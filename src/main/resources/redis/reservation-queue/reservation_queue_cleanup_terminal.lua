-- KEYS: terminal ZSET, ticket HASH, stream, admitted, waiting, processing, retry, deadline
-- ARGV: ticketId, streamId, completedBeforeMs
if redis.call('EXISTS', KEYS[2]) == 0 then
    redis.call('ZREM', KEYS[1], ARGV[1])
    return 'ORPHAN_INDEX'
end
local status = redis.call('HGET', KEYS[2], 'status')
if status ~= 'SUCCEEDED' and status ~= 'FAILED_FINAL' and status ~= 'EXPIRED' then
    return 'NOT_TERMINAL'
end
local score = tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0')
if score == 0 or score > tonumber(ARGV[3]) then return 'NOT_DUE' end
if redis.call('HGET', KEYS[2], 'streamId') ~= ARGV[2] then return 'MISMATCH' end

redis.call('XDEL', KEYS[3], ARGV[2])
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[1], ARGV[1])
for index = 4, 8 do redis.call('ZREM', KEYS[index], ARGV[1]) end
return 'CLEANED'
