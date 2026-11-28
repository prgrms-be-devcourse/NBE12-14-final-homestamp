-- [혼자 잠그기]로 잠근 점검의 잠긴 이유(#392, 05 inspection.lock_reason, 06 INSP-1g).
-- 상대 사정(상대 없음·상대 공유 중단·상대 탈퇴·초대 거절)으로 요청한 측이 혼자 잠근 점검은 SOLO("단독 기록")이고, 항목이 없어도 같다.
-- 구체적인 혼자 진행 이유는 그 LOCK 행동 payload의 solo_reason에 남는다. 기존 값은 그대로라 줄을 바꾸지 않는다.
ALTER TABLE state.inspection DROP CONSTRAINT inspection_lock_reason_check;

ALTER TABLE state.inspection
    ADD CONSTRAINT inspection_lock_reason_check CHECK (lock_reason IN ('ALL_RESOLVED', 'NO_ITEMS', 'SOLO'));
