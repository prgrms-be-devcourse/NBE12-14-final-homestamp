-- 행동 해시를 계산한 입력 형식의 버전 (05-ERD-초안 2-3 action, 04 4-0 해시 체인, #72)
-- 해시는 거꾸로 풀 수 없어서, 저장된 해시를 다시 계산하려면 어느 규칙으로 계산했는지 해시 밖에 적어 두어야 한다.
-- 지금 형식은 1이다(ActionHash.VERSION). 형식을 바꾸면 새 행동부터 다음 번호를 쓰고, 옛 행동은 적힌 번호의 규칙으로 검증한다.
-- 이 값은 행동 해시 입력의 맨 앞 칸이기도 하다.
--
-- 기본값을 두지 않는다: 앱이 넣지 않으면 저장이 실패해야 한다(request_id와 같은 이유).
-- 이 마이그레이션 전에 저장된 행동이 있으면 NOT NULL 추가가 실패한다. 지금은 행동을 쓰는 기능이 없어 비어 있다.

ALTER TABLE record.action
    ADD COLUMN hash_version smallint NOT NULL
        CONSTRAINT ck_action_hash_version CHECK (hash_version >= 1);
