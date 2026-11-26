-- 선착순 당첨 + 보상 기록 원자 처리 (단일 스크립트 = 락 없이 원자성 보장, ADR-0007·0019)
-- KEYS[1] = event:{id}:participants (Set)    참여자 전체 (중복 참여 방지)
-- KEYS[2] = event:{id}:stock        (String) 남은 재고. 키가 없으면 이벤트 미개설/마감
-- KEYS[3] = event:{id}:winners      (Set)    당첨자 (원장과 대사 기준)
-- KEYS[4] = event:{id}:amount       (String) 당첨 지급 포인트 (없으면 ARGV[3])
-- KEYS[5] = event:{id}:rewards      (Stream) 보상 기록 → core-service 가 원장에 반영
-- ARGV[1] = userId, ARGV[2] = eventId, ARGV[3] = 기본 지급 포인트, ARGV[4] = occurredAt (ISO-8601)
-- return: 1 = 당첨, 0 = 낙첨(재고 소진), -1 = 중복 참여, -2 = 이벤트 없음/마감
if redis.call('EXISTS', KEYS[2]) == 0 then
  return -2
end
if redis.call('SADD', KEYS[1], ARGV[1]) == 0 then
  return -1
end
local remain = redis.call('DECR', KEYS[2])
if remain < 0 then
  redis.call('INCR', KEYS[2])
  return 0
end
redis.call('SADD', KEYS[3], ARGV[1])
local amount = redis.call('GET', KEYS[4]) or ARGV[3]
-- 필드는 point.reward.requested 스키마와 같다 (api/events/point.reward.requested.schema.json)
redis.call('XADD', KEYS[5], '*',
  'idempotencyKey', ARGV[2] .. ':' .. ARGV[1],
  'userId', ARGV[1],
  'eventId', ARGV[2],
  'amount', amount,
  'reason', 'QUIZ_REWARD',
  'occurredAt', ARGV[4])
return 1
