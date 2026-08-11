-- KEYS: processing ZSET, ticket HASH
-- ARGV: ticketId, streamId, ownerToken, workerId, recoveredAtMs, leaseUntilMs, retentionMs
if redis.call('EXISTS', KEYS[2]) == 0 then return 'MISSING' end
local status = redis.call('HGET', KEYS[2], 'status')
if status == 'SUCCEEDED' or status == 'FAILED_FINAL' then return 'ALREADY_TERMINAL' end
if status ~= 'PROCESSING' then return 'NOT_WAITING' end
if redis.call('HGET', KEYS[2], 'streamId') ~= ARGV[2]
        or redis.call('HGET', KEYS[2], 'ownerToken') ~= ARGV[3] then
    return 'PAYLOAD_MISMATCH'
end
local leaseUntil = tonumber(redis.call('HGET', KEYS[2], 'workerLeaseUntil') or '0')
if leaseUntil > tonumber(ARGV[5]) then return 'LEASE_ACTIVE' end

redis.call('ZADD', KEYS[1], ARGV[6], ARGV[1])
redis.call('HSET', KEYS[2], 'workerId', ARGV[4], 'claimedAt', ARGV[5], 'workerLeaseUntil', ARGV[6])
redis.call('PEXPIRE', KEYS[1], ARGV[7])
redis.call('PEXPIRE', KEYS[2], ARGV[7])
return 'RECOVERED'
