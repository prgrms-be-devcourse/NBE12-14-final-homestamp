-- 한 요청으로 남긴 행동을 묶는 값 (05-ERD-초안 2-3 action, 06 4-0 멱등, #66)
-- pipeline이 요청마다 UUID v7을 하나 만들어 그 요청의 모든 행동(딸린 서버 행동 포함)에 같은 값을 넣는다.
-- 다시 온 요청은 첫 행동을 (actor_key, idempotency_key)로 찾고, 나머지는 그 행동의 request_id로 모은다.
-- 같은 호실의 두 회원이 같은 키를 써도 섞이지 않게 하려고, 딸린 행동의 멱등 키도 <request_id>#n으로 쓴다.
--
-- FK는 두지 않는다: 첫 행동이 초안 지우기(05 2-10)로 지워져도 남은 행동이 깨지지 않게.
-- 행동 해시 입력에 포함한다(입력 형식은 ActionWriter 구현 때 테스트로 고정).
-- 기본값을 두지 않는다: 앱이 넣지 않으면 저장이 실패해야 한다. 이 마이그레이션 전에 저장된 행동이 있으면
-- NOT NULL 추가가 실패한다(행동 기록은 UPDATE 트리거로 고칠 수 없어 채워 넣을 방법이 없음). 지금은 행동을 쓰는 기능이 없어 비어 있다.

ALTER TABLE record.action ADD COLUMN request_id uuid NOT NULL;

CREATE INDEX ix_action_request ON record.action (request_id);
