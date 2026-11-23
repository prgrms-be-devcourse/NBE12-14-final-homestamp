-- 숨긴 부위 다시 보이기 행동(SHOW_PART, #136, 03 "부위", 06 ROOM-3).
-- 부위는 지우지 않고 숨기기만 하는데(HIDE_PART) 되돌리는 행동이 없었다. 숨긴 부위에는 새 기록을 받지 않아서(#134)
-- 다시 보이게 할 수 있어야 나중에 그 부위에 하자를 등록할 수 있다. 설비·가전 켜고 끔(TOGGLE_PART, room_part.enabled)과는
-- 다른 일이다(숨김은 room_part.hidden_at).
--
-- PostgreSQL은 CHECK를 그 자리에서 고칠 수 없어서 같은 이름으로 지웠다가 다시 만든다(한 마이그레이션 = 한 트랜잭션).
-- 목록은 V1과 같고 'SHOW_PART'만 더한다. ActionType과 같아야 한다(ActionTypeTest가 가장 최근 V 파일의 목록과 맞댄다).

ALTER TABLE record.action DROP CONSTRAINT ck_action_type;

ALTER TABLE record.action
    ADD CONSTRAINT ck_action_type CHECK (type IN (
        -- 호실·참여
        'CREATE_ROOM', 'EDIT_ROOM_INFO', 'ADD_PART', 'HIDE_PART', 'SHOW_PART', 'TOGGLE_PART',
        'INVITE', 'CHANGE_INVITE_PHONE', 'REJECT_INVITE', 'CONNECT', 'JOIN',
        'SHARE_CONSENT', 'STOP_SHARING', 'RESUME_SHARING',
        'MOVE_OUT', 'REQUEST_END', 'CONFIRM_END', 'REJECT_END', 'CONFIRM_LIVING',
        -- 링크·통지
        'LINK_VIEW', 'OPT_OUT', 'RESEND_NOTICE', 'RENOTIFY', 'MARK_UNCONFIRMED', 'MARK_UNDELIVERED',
        -- 수리
        'REGISTER', 'SUPPLEMENT', 'RESPOND_DEFECT', 'START_REPAIR', 'REQUEST_CLOSE', 'CONFIRM_CLOSE',
        'NOT_YET', 'CLOSE_ALONE', 'REOPEN', 'ADD_PHOTO', 'ASK_PROGRESS', 'NOTE_PROGRESS',
        'RESPOND_SITUATION', 'NOTE_CAUSE', 'RESPOND_CAUSE',
        -- 고치기
        'CORRECT', 'RESPOND_CORRECTION', 'ACCEPT_ALL_CORRECTIONS', 'RESUBMIT_CORRECTION', 'WITHDRAW_CORRECTION',
        -- 돈·제안
        'RECORD_PAYMENT', 'RESPOND_AMOUNT', 'SUPPLEMENT_PAYMENT',
        'PROPOSE', 'AGREE', 'DISPUTE_PROPOSAL', 'COUNTER', 'WITHDRAW',
        -- 점검
        'START_INSPECTION', 'ADD_ITEM', 'ADD_PART_PHOTO', 'REQUEST_INSPECTION_CONFIRM', 'RESPOND_ITEM',
        'SUPPLEMENT_ITEM', 'CONFIRM_ALL', 'LOCK', 'FINISH_INSPECTION',
        -- 정산
        'OPEN_SETTLEMENT', 'INCLUDE_CANDIDATE', 'ADD_SETTLEMENT_ITEM', 'PROPOSE_EXCLUDE',
        'RECORD_TRANSFER', 'RESPOND_TRANSFER', 'PROPOSE_FINISH', 'CONFIRM_FINISH', 'NOT_YET_FINISH',
        'FINISH_ALONE', 'CLOSE_FINISH_REQUEST', 'AUTOCLOSE',
        -- 사진
        'HIDE_PHOTO', 'SHOW_PHOTO', 'MARK_PHOTO_WRONG', 'UNMARK_PHOTO_WRONG', 'ERASE_PHOTO'));
