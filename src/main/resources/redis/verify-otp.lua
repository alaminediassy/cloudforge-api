-- KEYS: hash, cooldown, attempts, blocked.
-- ARGV: candidate hash, max attempts, attempts ms, block ms.
-- Returns: 0 invalid, 1 accepted, 2 blocked.
-- An earlier failure may leave the attempt limit without a block key.
-- Check the counter before accepting any code.
local previous = redis.call('GET', KEYS[3])
local count = previous and tonumber(previous) or 0
if previous and (not tonumber(previous) or count < 0 or count % 1 ~= 0) then
    return redis.error_reply('Invalid OTP attempt state')
end
-- Older separate INCR/EXPIRE calls could leave a counter with no expiry.
-- Add only a missing expiry. Keep the value and any existing TTL.
if previous and redis.call('PTTL', KEYS[3]) == -1 then
    redis.call('PEXPIRE', KEYS[3], ARGV[3])
end
if redis.call('EXISTS', KEYS[4]) == 1 then return 2 end
local stored = redis.call('GET', KEYS[1])
if not stored then return 0 end
if count >= tonumber(ARGV[2]) then
    redis.call('SET', KEYS[4], '1', 'PX', ARGV[4])
    redis.call('DEL', KEYS[1])
    return 2
end
if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
local attempts = redis.call('INCR', KEYS[3])
if not previous then redis.call('PEXPIRE', KEYS[3], ARGV[3]) end
if attempts >= tonumber(ARGV[2]) then
    redis.call('SET', KEYS[4], '1', 'PX', ARGV[4])
    redis.call('DEL', KEYS[1])
    return 2
end
return 0
