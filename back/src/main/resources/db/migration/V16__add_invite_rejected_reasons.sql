-- 초대 거절(REJECT_INVITE)의 이유 값을 네 CHECK에 더한다(#421·#392, H-1).
-- response_request.closed_reason: 거절 때 초대한 측이 요청한 열린 요청을 닫은 이유.
-- response_request.solo_reason: 상대 자리가 거절됨이라 거주 종료·점검 확인을 혼자 진행한 이유.
-- link.invalidated_reason: 거절된 자리로 보낸 초대 링크를 닫은 이유.
-- correction_request.closed_reason: 거절 때 초대한 측이 남긴 확인 전 고친 내용을 그 확인 요청과 함께 닫은 이유(#478, room 거절 저장이 닫음).
-- 기존 값은 그대로 두고 값만 더한다. 이름은 V1 컬럼 CHECK의 자동 이름({표}_{칸}_check) 그대로 다시 만든다.
-- 이름이 다르면 DROP이 실패해 이 파일 전체가 되돌아간다(IF EXISTS를 쓰지 않아 다른 제약을 남기거나 지우지 않는다).

ALTER TABLE state.response_request DROP CONSTRAINT response_request_closed_reason_check;

ALTER TABLE state.response_request
    ADD CONSTRAINT response_request_closed_reason_check CHECK (closed_reason IN (
        'SHARING_STOPPED', 'COUNTERPART_WITHDRAWN', 'SETTLED', 'CLOSED_BY_BOTH',
        'SETTLEMENT_CHANGED', 'AMOUNT_CHANGED', 'ROOM_ENDED', 'INVITE_REJECTED'));

ALTER TABLE state.response_request DROP CONSTRAINT response_request_solo_reason_check;

ALTER TABLE state.response_request
    ADD CONSTRAINT response_request_solo_reason_check CHECK (solo_reason IN (
        'UNCONFIRMED', 'OPTED_OUT', 'COUNTERPART_WITHDRAWN', 'SHARING_STOPPED',
        'SMS_UNDELIVERED', 'NOT_CONNECTED', 'NO_COUNTERPART', 'INVITE_REJECTED'));

ALTER TABLE state.link DROP CONSTRAINT link_invalidated_reason_check;

ALTER TABLE state.link
    ADD CONSTRAINT link_invalidated_reason_check CHECK (invalidated_reason IN (
        'REINVITED', 'CONNECTED', 'RESIDENCE_ENDED', 'ISSUER_WITHDRAWN', 'SHARING_STOPPED', 'SETTLED',
        'INVITE_REJECTED'));

ALTER TABLE state.correction_request DROP CONSTRAINT correction_request_closed_reason_check;

ALTER TABLE state.correction_request
    ADD CONSTRAINT correction_request_closed_reason_check CHECK (closed_reason IN (
        'SHARING_STOPPED', 'COUNTERPART_WITHDRAWN', 'SETTLED', 'CLOSED_BY_BOTH', 'INVITE_REJECTED'));
