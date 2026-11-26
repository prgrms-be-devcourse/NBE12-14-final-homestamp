-- 전달 결과 조회(#120). 조회 세대(lease)와 전달 확인 기한을 notice에 더한다.
--
-- delivery_check_due_at: 보낸(SENT) 통지가 전달 결과 없이 기다리는 마지막 시각. SENT로 바꿀 때 보낸 시각과 그때의
-- delivery.unknown-after로 한 번 정해 저장하고 바꾸지 않는다(06 0-1, 03 1-1 "시간 값"). 설정을 바꿔도 이미 보낸 통지의 기한은 그대로다.
--
-- 기존 행은 채우지 않는다: 보낸 당시의 설정을 기록에서 알 수 없어 기한을 복원할 근거가 없다. 기한이 없는 SENT 행은 전달 결과 작업자가 가져가지 않는다.
-- "SENT이면 기한이 있다"는 DB 제약은 이 버전에 넣지 않는다. 이전 버전 앱(기한을 쓰지 않는 markSent)이 블루/그린 전환 중 같은 DB에 SENT를 적을 수 있어서다
-- (04 8장 "스키마 변경: 이전 버전 앱과도 맞게"). 새 앱은 엔티티에서 기한 없이 SENT로 바꾸지 않는다.
ALTER TABLE state.notice
    ADD COLUMN delivery_poll_token uuid,
    ADD COLUMN delivery_poll_started_at timestamptz,
    ADD COLUMN delivery_poll_protected_until timestamptz,
    ADD COLUMN delivery_check_due_at timestamptz,
    ADD CONSTRAINT ck_notice_delivery_poll_lease CHECK (
        (delivery_poll_started_at IS NULL AND delivery_poll_protected_until IS NULL)
        OR (delivery_poll_token IS NOT NULL AND delivery_poll_started_at IS NOT NULL
            AND delivery_poll_protected_until > delivery_poll_started_at)
    ),
    ADD CONSTRAINT ck_notice_delivery_check_due CHECK (
        delivery_check_due_at IS NULL
        OR (sent_at IS NOT NULL AND delivery_check_due_at > sent_at)
    );
