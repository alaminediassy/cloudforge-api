-- KEYS: hash, cooldown, attempts (unused here), blocked.
-- ARGV: hash, OTP ms, cooldown ms. Returns: 0 cooldown, 1 stored, 2 blocked.
-- Keep the cooldown even if an earlier failure left no code hash.
if redis.call('EXISTS', KEYS[4]) == 1 then return 2 end
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
-- A resend must not reset the current failed-attempt window.
return 1
