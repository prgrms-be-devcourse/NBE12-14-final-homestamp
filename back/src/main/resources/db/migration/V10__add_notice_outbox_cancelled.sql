-- 보내기 전에 취소한 통지·문자 줄을 FAILED와 구분해 남긴다(#100 U2, 번호를 고쳐 다시 초대).
-- notice: REQUESTED → CANCELLED, outbox: PENDING → CANCELLED만 쓴다. 전이 규칙은 엔티티가 지키고, 여기서는 값만 더한다.
-- 기존 V1은 유지하고 두 CHECK만 다시 만든다. 제약 이름은 V1의 이름 없는 열 CHECK에 PostgreSQL이 붙인 이름이다.
-- 기존 값은 모두 그대로 허용하므로 저장된 행은 바꾸지 않는다.

ALTER TABLE state.notice DROP CONSTRAINT notice_status_check;

ALTER TABLE state.notice
    ADD CONSTRAINT notice_status_check
        CHECK (status IN ('REQUESTED', 'SENT', 'DELIVERED', 'VIEWED', 'FAILED', 'CANCELLED'));

ALTER TABLE state.outbox DROP CONSTRAINT outbox_status_check;

ALTER TABLE state.outbox
    ADD CONSTRAINT outbox_status_check
        CHECK (status IN ('PENDING', 'SENDING', 'DONE', 'FAILED', 'CANCELLED'));
