-- 회원 인증 번호의 번호 HMAC(#462, #83 Q2). 링크 그만 받기가 원문 번호 없이 "인증된 활동 회원의 번호인가"를 보려고 둔다.
-- 값은 PhoneHmacs(auth PhoneHmacService)와 같은 계산이다(sms_opt_out.phone_hmac과 같은 값).
--
-- 이전 앱과 함께 도는 동안(블루/그린, 04 8장 "스키마 변경") 맞도록 두 단계로 나눈다.
--   1단계(이 파일): 칸은 비워 둘 수 있다. HMAC이 있으면 번호도 있어야 한다.
--     키는 마이그레이션에 없으므로 기존 행은 앱이 채운다(auth MemberPhoneHmacFill).
--     이전 앱은 이 칸을 모르고 번호만 바꾸므로(번호 해제 ACC-3a), 번호만 바뀐 UPDATE는 HMAC을 비운다(아래 트리거).
--     그래서 HMAC은 "비었거나 지금 번호의 값"이고, 빈 값은 앱이 다시 채운다.
--   2단계(다음 V 파일): 이전 앱이 모두 내려가고 채우기가 끝난 뒤 (phone IS NULL) = (phone_hmac IS NULL)로 좁히고 트리거를 뺀다.

ALTER TABLE state.member ADD COLUMN phone_hmac varchar(128);

-- 번호 유니크(ux_member_phone, 불변식 8)와 같은 뜻. 트리거가 남은 값을 비워서 이전 앱이 번호를 풀어도 걸리지 않는다
CREATE UNIQUE INDEX ux_member_phone_hmac ON state.member (phone_hmac) WHERE phone_hmac IS NOT NULL;

ALTER TABLE state.member
    ADD CONSTRAINT ck_member_phone_hmac_needs_phone CHECK (phone_hmac IS NULL OR phone IS NOT NULL);

-- 아직 채우지 않은 행. 회원 확인(빈 행이 있으면 예외)이 번호 잠금을 잡은 채 매번 보고 채우기도 이 조건으로 읽으므로 전체를 훑지 않게 둔다.
-- 채우면 빠져서 작게 남는다. 2단계에서 뺀다
CREATE INDEX ix_member_phone_hmac_pending ON state.member (id) WHERE phone IS NOT NULL AND phone_hmac IS NULL;

-- 번호가 바뀌었는데 HMAC은 그대로인 UPDATE(이 칸을 모르는 이전 앱)면 HMAC을 비운다. 새 앱은 두 칸을 함께 바꾸므로 건드리지 않는다
CREATE FUNCTION state.clear_stale_member_phone_hmac() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.phone IS DISTINCT FROM OLD.phone AND NEW.phone_hmac IS NOT DISTINCT FROM OLD.phone_hmac THEN
    NEW.phone_hmac := NULL;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_member_phone_hmac_follows_phone
    BEFORE UPDATE ON state.member
    FOR EACH ROW EXECUTE FUNCTION state.clear_stale_member_phone_hmac();
