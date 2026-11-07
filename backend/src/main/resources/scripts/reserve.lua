-- KEYS[1] = stock:<productId>
-- ARGV[1] = qty
-- Returns 1 if reserved, 0 if insufficient stock.

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
local qty = tonumber(ARGV[1])

if stock >= qty then
  redis.call('DECRBY', KEYS[1], qty)
  return 1
else
  return 0
end