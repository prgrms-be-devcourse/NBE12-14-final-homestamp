-- V1__init_schema.sql
-- HOMESTAMP 전체 스키마 (37개 테이블)
-- 기준: docs/rules/06-요구사항-명세.md(기준점), 테이블·칸은 docs/rules/05-ERD-초안.md 2장
-- id는 Hibernate가 넣는 UUID v7, created_at은 앱 Clock (DEFAULT 없음, 불변식 10)
-- enum은 varchar + CHECK (JPA @Enumerated(STRING), ddl-auto=validate)
-- 뒤에서 만드는 테이블을 가리키는 FK는 14절에서 ALTER로 붙인다(순환 참조)

-- =========================================================
-- 0. 덧붙이기만 하는 테이블 보호 ([A]·[B], 05 2-3·04 4-0)
--    UPDATE는 언제나 거절, DELETE는 보관 삭제 경로(SET LOCAL app.purge = 'on')에서만
-- =========================================================
CREATE FUNCTION forbid_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('app.purge', true) = 'on' THEN
    RETURN OLD;
END IF;
  RAISE EXCEPTION '% on % is not allowed (append-only)', TG_OP, TG_TABLE_NAME;
END;
$$;

-- 무효가 된 링크는 다시 유효해지지 않는다(불변식 7, 05 3장)
CREATE FUNCTION forbid_link_revalidate() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.invalidated_at IS NOT NULL AND NEW.invalidated_at IS DISTINCT FROM OLD.invalidated_at THEN
    RAISE EXCEPTION 'link % cannot be revalidated', OLD.id;
END IF;
RETURN NEW;
END;
$$;

-- =========================================================
-- 1. 계정 (05 2-1)
-- =========================================================
CREATE TABLE member (
                        id                 uuid PRIMARY KEY,
                        email              varchar(320),                 -- 탈퇴하면 NULL
                        password_hash      varchar(255),
                        display_name       varchar(50),
                        phone              varchar(20),                         -- 인증 번호, 다른 계정이 인증하면 NULL
                        phone_verified_at  timestamptz,
                        sms_enabled        boolean     NOT NULL DEFAULT true,
                        status             varchar(10) NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
                        withdrawn_at       timestamptz,
                        created_at         timestamptz NOT NULL,
                        version            bigint      NOT NULL,
                        CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL))
);

CREATE TABLE refresh_token (
                               id              uuid PRIMARY KEY,
                               member_id       uuid         NOT NULL REFERENCES member (id),
                               family_id       uuid         NOT NULL,
                               token_hash      varchar(128) NOT NULL UNIQUE,
                               device_label    varchar(100),
                               expires_at      timestamptz  NOT NULL,
                               revoked_at      timestamptz,
                               replaced_by_id  uuid REFERENCES refresh_token (id),
                               created_at      timestamptz  NOT NULL,
                               version         bigint       NOT NULL
);

CREATE TABLE phone_verification (
                                    id             uuid PRIMARY KEY,
                                    phone          varchar(20)  NOT NULL,
                                    purpose        varchar(10)  NOT NULL CHECK (purpose IN ('SIGNUP', 'RESET', 'FIND_ID', 'CHANGE')),
                                    code_hash      varchar(128) NOT NULL,
                                    expires_at     timestamptz  NOT NULL,
                                    attempt_count  int          NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
                                    verified_at    timestamptz,
                                    consumed_at    timestamptz,                             -- 인증 표를 쓴 시각(06 4-1, ACC-1f)
                                    created_at     timestamptz  NOT NULL,
                                    version        bigint       NOT NULL,
                                    CHECK (consumed_at IS NULL OR verified_at IS NOT NULL)
);

CREATE TABLE member_acknowledgement (
                                        id            uuid PRIMARY KEY,
                                        member_id     uuid        NOT NULL REFERENCES member (id),
                                        kind          varchar(20) NOT NULL CHECK (kind IN ('AGE_14_CONFIRMED', 'TERMS_AGREED', 'PRIVACY_NOTICE_ACK')),
                                        text_version  varchar(20) NOT NULL,
                                        agreed_at     timestamptz NOT NULL,
                                        created_at    timestamptz NOT NULL
);

CREATE TABLE sms_opt_out (
                             phone_hmac  varchar(128) PRIMARY KEY,
                             created_at  timestamptz  NOT NULL
);

-- =========================================================
-- 2. 호실과 참여 (05 2-2, link는 7절)
-- =========================================================
CREATE TABLE part_template (
                               id          uuid PRIMARY KEY,
                               zone        varchar(30) NOT NULL,
                               name        varchar(50) NOT NULL,
                               sort_order  int         NOT NULL,
                               optional    boolean     NOT NULL DEFAULT false            -- 설비·가전처럼 호실마다 켜고 끄는 부위
);

-- 법정동 기본 데이터는 별도 V 파일에서 적재(05 2-2)
CREATE TABLE region (
    code        char(10) PRIMARY KEY CHECK (code ~ '^[0-9]{10}$'),
    sido        varchar(20) NOT NULL,
    sigungu     varchar(30),                                    -- 세종특별자치시는 시·군·구가 없어 NULL
    dong        varchar(30) NOT NULL,
    active      boolean NOT NULL DEFAULT true
);

CREATE TABLE room_record (
                             id                              uuid PRIMARY KEY,
                             region_code                     char(10) NOT NULL REFERENCES region (code),
                             alias                           varchar(50) NOT NULL,
                             move_in_date                    date,
                             status                          varchar(10) NOT NULL CHECK (status IN ('LIVING', 'ENDED', 'SETTLED')),
                             created_by_side                 varchar(10) NOT NULL CHECK (created_by_side IN ('TENANT', 'LANDLORD')),
                             counterpart_first_connected_at  timestamptz,           -- NULL이면 탈퇴 때 통째 삭제
                             ended_at                        timestamptz,
                             settled_at                      timestamptz,
                             autoclose_due_at                timestamptz,
                             delete_due_at                   timestamptz,
                             inactivity_notice_due_at        timestamptz,
                             joined_into_id                  uuid REFERENCES room_record (id),
                             joined_at                       timestamptz,
                             created_at                      timestamptz NOT NULL,
                             version                         bigint      NOT NULL,
                             CHECK ((joined_into_id IS NULL) = (joined_at IS NULL)),
                             CHECK (joined_into_id <> id),
                             CHECK (status = 'LIVING' OR ended_at IS NOT NULL),
                             CHECK (status <> 'SETTLED' OR settled_at IS NOT NULL)
);

CREATE TABLE party (
                       id                  uuid PRIMARY KEY,
                       room_record_id      uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                       side                varchar(10) NOT NULL CHECK (side IN ('TENANT', 'LANDLORD')),
                       member_id           uuid REFERENCES member (id),
                       invite_phone        varchar(20),                         -- 문자를 보내야 해서 평문, 연결·수신 거부·탈퇴 때 NULL
                       status              varchar(20) NOT NULL CHECK (status IN (
                                                                                  'EMPTY', 'NUMBER_ONLY', 'INVITED', 'CONNECTED',
                                                                                  'SHARING_STOPPED', 'REJECTED', 'WITHDRAWN')),
                       connected_at        timestamptz,
                       sharing_stopped_at  timestamptz,
                       created_at          timestamptz NOT NULL,
                       version             bigint      NOT NULL,
                       CONSTRAINT uq_party_room_side   UNIQUE (room_record_id, side),
                       CONSTRAINT uq_party_room_member UNIQUE (room_record_id, member_id),   -- 불변식 13
                       CHECK (status NOT IN ('CONNECTED', 'SHARING_STOPPED') OR member_id IS NOT NULL),
                       CHECK (status NOT IN ('NUMBER_ONLY', 'INVITED') OR invite_phone IS NOT NULL),
                       CHECK (status <> 'SHARING_STOPPED' OR sharing_stopped_at IS NOT NULL)
);

CREATE TABLE room_part (
                           id              uuid PRIMARY KEY,
                           room_record_id  uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                           template_id     uuid REFERENCES part_template (id),    -- 직접 추가한 부위면 NULL
                           zone            varchar(30) NOT NULL,
                           name            varchar(50) NOT NULL,
                           sort_order      int         NOT NULL,
                           enabled         boolean     NOT NULL DEFAULT true,
                           hidden_at       timestamptz,                           -- 지우지 않고 숨기기만
                           created_at      timestamptz NOT NULL,
                           version         bigint      NOT NULL
);

-- =========================================================
-- 3. 수리 건과 내용 판 (05 2-5)
-- =========================================================
CREATE TABLE repair_case (
                             id                    uuid PRIMARY KEY,
                             room_record_id        uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                             current_content_id    uuid        NOT NULL,             -- FK는 14절(지연 검사)
                             confirmed_content_id  uuid,                             -- 같이 확인한 모습, FK는 14절
                             status                varchar(20) NOT NULL CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'CLOSE_WAIT', 'DONE')),
                             closed_by             varchar(10) CHECK (closed_by IN ('BOTH', 'TENANT', 'LANDLORD')),
                             defect_ack            varchar(20) NOT NULL CHECK (defect_ack IN ('PENDING', 'ACK', 'NEED_MORE', 'DISPUTE', 'UNCONFIRMED')),
                             money_status          varchar(20) NOT NULL CHECK (money_status IN ('UNDECIDED', 'PENDING', 'AGREED', 'DISPUTE', 'NO_COST')),
                             current_proposal_id   uuid,                             -- FK는 14절
                             agreed_proposal_id    uuid,                             -- FK는 14절
                             previous_case_id      uuid, -- 재발
                             related_case_id       uuid, -- 수리 중 새로 찾은 별개 하자면 원래 건
                             created_by_side       varchar(10) NOT NULL CHECK (created_by_side IN ('TENANT', 'LANDLORD')),
                             solo                  boolean     NOT NULL DEFAULT false,
                             first_shown_at        timestamptz,                      -- NULL이면 탈퇴 때 삭제 대상
                             created_at            timestamptz NOT NULL,
                             version               bigint      NOT NULL,
                             CHECK ((status = 'DONE') = (closed_by IS NOT NULL)),
                             CHECK (previous_case_id <> id),
                             CHECK (related_case_id <> id)
);

CREATE TABLE repair_content (
                                id               uuid PRIMARY KEY,
                                room_record_id   uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                repair_case_id   uuid         NOT NULL,
                                no               int          NOT NULL CHECK (no >= 1),
  room_part_id     uuid         NOT NULL,
  title            varchar(100) NOT NULL,
  description      text,
  previous_id      uuid,
  created_by_side  varchar(10)  NOT NULL CHECK (created_by_side IN ('TENANT', 'LANDLORD')),
  created_at       timestamptz  NOT NULL,
  CONSTRAINT uq_repair_content_no UNIQUE (repair_case_id, no)
);

-- =========================================================
-- 4. 점검 (05 2-6)
-- =========================================================
CREATE TABLE inspection (
                            id              uuid PRIMARY KEY,
                            room_record_id  uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                            kind            varchar(10) NOT NULL CHECK (kind IN ('MOVE_IN', 'MOVE_OUT')),
                            author_side     varchar(10) NOT NULL CHECK (author_side IN ('TENANT', 'LANDLORD')),
                            status          varchar(10) NOT NULL CHECK (status IN ('DRAFT', 'REQUESTED', 'LOCKED')),
                            late            boolean     NOT NULL DEFAULT false,
                            locked_at       timestamptz,
                            lock_reason     varchar(20) CHECK (lock_reason IN ('ALL_RESOLVED', 'NO_ITEMS')),
                            first_shown_at  timestamptz,
                            created_at      timestamptz NOT NULL,
                            version         bigint      NOT NULL,
                            CONSTRAINT uq_inspection_kind UNIQUE (room_record_id, kind),
                            CHECK ((status = 'LOCKED') = (locked_at IS NOT NULL)),
                            CHECK ((locked_at IS NULL) = (lock_reason IS NULL))
);

CREATE TABLE inspection_item (
                                 id                    uuid PRIMARY KEY,
                                 room_record_id        uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                 inspection_id         uuid        NOT NULL,
                                 current_version_id    uuid        NOT NULL,             -- FK는 14절(지연 검사)
                                 confirmed_version_id  uuid,                             -- FK는 14절
                                 status                varchar(20) NOT NULL CHECK (status IN ('PENDING', 'ACK', 'NEED_MORE', 'DISPUTE', 'UNCONFIRMED')),
                                 added_by_side         varchar(10) NOT NULL CHECK (added_by_side IN ('TENANT', 'LANDLORD')),
                                 added_after_lock      boolean     NOT NULL DEFAULT false,
                                 added_after_end       boolean     NOT NULL DEFAULT false,
                                 created_at            timestamptz NOT NULL,
                                 version               bigint      NOT NULL
);

CREATE TABLE inspection_item_version (
                                         id                  uuid PRIMARY KEY,
                                         room_record_id      uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                         inspection_item_id  uuid        NOT NULL,
                                         no                  int         NOT NULL CHECK (no >= 1),
  room_part_id        uuid        NOT NULL,
  description         text        NOT NULL,
  previous_id         uuid,
  created_at          timestamptz NOT NULL,
  CONSTRAINT uq_inspection_item_version_no UNIQUE (inspection_item_id, no)
);

-- =========================================================
-- 5. 정산 (05 2-7)
-- =========================================================
CREATE TABLE settlement (
                            id                           uuid PRIMARY KEY,
                            room_record_id               uuid        NOT NULL UNIQUE REFERENCES room_record (id) ON DELETE CASCADE,
                            opened_at                    timestamptz NOT NULL,
                            deposit_status               varchar(20) NOT NULL CHECK (deposit_status IN ('NOT_ENTERED', 'PENDING', 'AGREED', 'DISPUTE')),
                            current_deposit_proposal_id  uuid,                      -- FK는 14절
                            agreed_deposit_proposal_id   uuid,                      -- FK는 14절
                            snapshot_no                  int         NOT NULL DEFAULT 1 CHECK (snapshot_no >= 1), -- 정산 변경마다 증가, 끝내기 확인의 seenVersionNo
                            created_at                   timestamptz NOT NULL,
                            version                      bigint      NOT NULL
);

CREATE TABLE settlement_item (
                                 id                   uuid PRIMARY KEY,
                                 room_record_id       uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                 settlement_id        uuid        NOT NULL,
                                 source               varchar(10) NOT NULL CHECK (source IN ('REPAIR', 'MANUAL')),
                                 repair_case_id       uuid,
                                 room_part_id         uuid,
                                 category             varchar(30) NOT NULL,
                                 status               varchar(10) NOT NULL CHECK (status IN ('PENDING', 'AGREED', 'DISPUTE', 'EXCLUDED')),
                                 current_proposal_id  uuid,                              -- FK는 14절
                                 agreed_proposal_id   uuid,                              -- FK는 14절
                                 created_at           timestamptz NOT NULL,
                                 version              bigint      NOT NULL,
                                 CHECK ((source = 'REPAIR') = (repair_case_id IS NOT NULL))
);

CREATE TABLE settlement_transfer (
                                     id                    uuid PRIMARY KEY,
                                     room_record_id        uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                     settlement_id         uuid        NOT NULL,
                                     direction             varchar(10) NOT NULL CHECK (direction IN ('REFUND', 'EXTRA')),
                                     current_version_id    uuid        NOT NULL,             -- FK는 14절(지연 검사)
                                     confirmed_version_id  uuid,                             -- FK는 14절
                                     recorded_by_side      varchar(10) NOT NULL CHECK (recorded_by_side IN ('TENANT', 'LANDLORD')),
                                     response              varchar(10) NOT NULL DEFAULT 'NONE' CHECK (response IN ('NONE', 'ACK', 'NEED_MORE', 'DISPUTE')),
                                     same_as_transfer_id   uuid,
                                     receipt_confirmed_at  timestamptz,
                                     created_at            timestamptz NOT NULL,
                                     version               bigint      NOT NULL,
                                     CHECK (same_as_transfer_id <> id)
);

CREATE TABLE settlement_transfer_version (
                                             id                      uuid PRIMARY KEY,
                                             room_record_id          uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                             settlement_transfer_id  uuid        NOT NULL,
                                             no                      int         NOT NULL CHECK (no >= 1),
  amount                  bigint      NOT NULL CHECK (amount >= 0),
  paid_on                 date        NOT NULL,
  previous_id             uuid,
  created_at              timestamptz NOT NULL,
  CONSTRAINT uq_settlement_transfer_version_no UNIQUE (settlement_transfer_id, no)
);

-- =========================================================
-- 6. 지불과 돈 안건 장치 (05 2-5·2-4)
-- =========================================================
CREATE TABLE payment (
                         id                    uuid PRIMARY KEY,
                         room_record_id        uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                         repair_case_id        uuid        NOT NULL,
                         current_version_id    uuid        NOT NULL,             -- FK는 14절(지연 검사)
                         confirmed_version_id  uuid,                             -- FK는 14절
                         confirm_strength      varchar(10) NOT NULL CHECK (confirm_strength IN ('ONE_SIDE', 'EVIDENCE', 'BOTH')),
                         response              varchar(10) NOT NULL DEFAULT 'NONE' CHECK (response IN ('NONE', 'ACK', 'NEED_MORE', 'DISPUTE')),
                         first_shown_at        timestamptz,
                         created_at            timestamptz NOT NULL,
                         version               bigint      NOT NULL
);

CREATE TABLE payment_version (
                                 id                uuid PRIMARY KEY,
                                 room_record_id    uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                 payment_id        uuid        NOT NULL,
                                 no                int         NOT NULL CHECK (no >= 1),
  payer_kind        varchar(20) NOT NULL CHECK (payer_kind IN ('TENANT', 'LANDLORD', 'THIRD_PARTY')),
  payer_label       varchar(30),                          -- 제3자면 이름 대신 관계(가족·윗집·보험사)
  payee_kind        varchar(20) NOT NULL CHECK (payee_kind IN ('VENDOR', 'COUNTERPART', 'THIRD_PARTY')),
  payee_label       varchar(30),
  amount            bigint      NOT NULL CHECK (amount >= 0),
  paid_on           date        NOT NULL,
  recorded_by_side  varchar(10) NOT NULL CHECK (recorded_by_side IN ('TENANT', 'LANDLORD')),
  evidence_kind     varchar(30),
  previous_id       uuid,
  created_at        timestamptz NOT NULL,
  CONSTRAINT uq_payment_version_no UNIQUE (payment_id, no)
);

CREATE TABLE proposal (
                          id                  uuid PRIMARY KEY,
                          room_record_id      uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                          repair_case_id      uuid,       -- 부담
                          settlement_item_id  uuid,   -- 정산 항목 금액·제외
                          settlement_id       uuid,        -- 보증금
                          proposed_by_side    varchar(10) NOT NULL CHECK (proposed_by_side IN ('TENANT', 'LANDLORD')),
                          counter_of_id       uuid,
                          replaces_id         uuid,       -- 답 전에 바꾼 내 이전 제안
                          round               int         NOT NULL CHECK (round >= 1),
                          no_cost             boolean     NOT NULL DEFAULT false,
                          exclude             boolean     NOT NULL DEFAULT false,
                          reason              text,                                    -- 정산 항목 [바꾸기]의 사유(06 SET-2d), 금액·방향은 제안 줄
                          created_at          timestamptz NOT NULL,
                          CHECK (num_nonnulls(repair_case_id, settlement_item_id, settlement_id) = 1),
                          CHECK (NOT exclude OR settlement_item_id IS NOT NULL)
);

CREATE TABLE proposal_line (
                               id                 uuid PRIMARY KEY,
                               room_record_id     uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                               proposal_id        uuid        NOT NULL,
                               line_kind          varchar(20) NOT NULL CHECK (line_kind IN ('TENANT', 'LANDLORD', 'THIRD_PARTY', 'DEDUCT', 'ADD', 'DEPOSIT')),
                               third_party_label  varchar(30),
                               amount             bigint      NOT NULL CHECK (amount >= 0),
                               created_at         timestamptz NOT NULL,
                               CHECK (line_kind = 'THIRD_PARTY' OR third_party_label IS NULL)
);

-- =========================================================
-- 7. 링크 (05 2-2)
-- =========================================================
CREATE TABLE link (
                      id                   uuid PRIMARY KEY,
                      room_record_id       uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                      kind                 varchar(20)  NOT NULL CHECK (kind IN ('INVITE', 'SETTLEMENT_INVITE')),
                      party_id             uuid         NOT NULL,
                      repair_case_id       uuid,    -- 공유한 기록(없으면 호실 초대 화면)
                      inspection_id        uuid,
                      token_hash           varchar(128) NOT NULL UNIQUE,
                      issued_by_member_id  uuid         NOT NULL REFERENCES member (id),
                      invalidated_at       timestamptz,
                      invalidated_reason   varchar(30),
                      failed_view_count    int          NOT NULL DEFAULT 0 CHECK (failed_view_count >= 0),
                      created_at           timestamptz  NOT NULL,
                      version              bigint       NOT NULL,
                      CHECK (num_nonnulls(repair_case_id, inspection_id) <= 1),
                      CHECK (kind <> 'SETTLEMENT_INVITE' OR num_nonnulls(repair_case_id, inspection_id) = 0)
);

-- =========================================================
-- 8. 응답 요청 (05 2-4, 기다림 타이머)
-- =========================================================
CREATE TABLE response_request (
                                  id                      uuid PRIMARY KEY,
                                  room_record_id          uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                  kind                    varchar(30) NOT NULL CHECK (kind IN (
                                                                                               'DEFECT_CONFIRM', 'SITUATION_CHECK', 'CORRECTION_CONFIRM', 'AMOUNT_CONFIRM',
                                                                                               'PROPOSAL', 'CLOSE_CONFIRM', 'INSPECTION_CONFIRM', 'RECEIPT_CONFIRM',
                                                                                               'SETTLE_FINISH', 'END_REQUEST', 'PROGRESS_CHECK')),
                                  repair_case_id          uuid,
                                  payment_id              uuid,
                                  settlement_item_id      uuid, -- 제안이 아니라 안건을 가리킴
                                  settlement_id           uuid,
                                  inspection_id           uuid,
                                  inspection_item_id      uuid,
                                  settlement_transfer_id  uuid,
                                  progress_note_id        uuid, -- SITUATION_CHECK가 확인하는 진행 상황
                                  requested_by_side       varchar(10) NOT NULL CHECK (requested_by_side IN ('TENANT', 'LANDLORD')),
                                  first_notified_at       timestamptz,                    -- min(전달됨, 링크 열람)
                                  renotify_due_at         timestamptz,
                                  unconfirmed_due_at      timestamptz,
                                  status                  varchar(20) NOT NULL CHECK (status IN ('WAITING', 'ANSWERED', 'UNCONFIRMED', 'WITHDRAWN', 'CLOSED')),
                                  closed_reason           varchar(30) CHECK (closed_reason IN (
                                      'SHARING_STOPPED', 'COUNTERPART_WITHDRAWN', 'SETTLED', 'CLOSED_BY_BOTH',
                                      'SETTLEMENT_CHANGED', 'AMOUNT_CHANGED', 'ROOM_ENDED')),
                                  closed_at               timestamptz,
                                  solo_reason             varchar(30) CHECK (solo_reason IN (
                                                                                             'UNCONFIRMED', 'OPTED_OUT', 'COUNTERPART_WITHDRAWN', 'SHARING_STOPPED',
                                                                                             'SMS_UNDELIVERED', 'NOT_CONNECTED', 'NO_COUNTERPART')),
                                  created_at              timestamptz NOT NULL,
                                  version                 bigint      NOT NULL,
                                  CHECK ((status = 'CLOSED') = (closed_reason IS NOT NULL)),
                                  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
                                  CHECK (num_nonnulls(repair_case_id, payment_id, settlement_item_id, settlement_id, inspection_id,
                                                      inspection_item_id, settlement_transfer_id, progress_note_id) <= 1),
                                  CHECK ((kind = 'SITUATION_CHECK') = (progress_note_id IS NOT NULL))
);

-- =========================================================
-- 9. 원인 설명·진행 상황·고친 내용 (05 2-5)
-- =========================================================
CREATE TABLE cause_note (
                            id              uuid PRIMARY KEY,
                            room_record_id  uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                            repair_case_id  uuid         NOT NULL,
                            side            varchar(10)  NOT NULL CHECK (side IN ('TENANT', 'LANDLORD')),
                            text            text         NOT NULL,
                            basis           varchar(10)  NOT NULL CHECK (basis IN ('GUESS', 'SEEN', 'EXPERT', 'HEARD')),
                            expert_name     varchar(50),
                            previous_id     uuid,         -- 고치면 언제나 새 행(06 REP-8c)
                            created_at      timestamptz  NOT NULL
);

CREATE TABLE progress_note (
                               id                  uuid PRIMARY KEY,
                               room_record_id      uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                               repair_case_id      uuid        NOT NULL,
                               side                varchar(10) NOT NULL CHECK (side IN ('TENANT', 'LANDLORD')),
                               text                text        NOT NULL,
                               answers_request_id  uuid,  -- 진행 상황 묻기에 대한 답이면
                               asks_check          boolean     NOT NULL DEFAULT false,      -- "상황이 바뀌었어요" 확인 요청
                               created_at          timestamptz NOT NULL
);

CREATE TABLE progress_note_response (
                                        id                uuid PRIMARY KEY,
                                        room_record_id    uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                        progress_note_id  uuid        NOT NULL UNIQUE,  -- 지금 답 하나
                                        side              varchar(10) NOT NULL CHECK (side IN ('TENANT', 'LANDLORD')),
                                        answer            varchar(10) NOT NULL CHECK (answer IN ('ACK', 'NEED_MORE', 'DISPUTE')),
                                        reason            text,
                                        created_at        timestamptz NOT NULL,
                                        version           bigint      NOT NULL,
                                        CHECK (answer = 'ACK' OR reason IS NOT NULL)
);

CREATE TABLE correction_request (
                                    id                      uuid PRIMARY KEY,
                                    room_record_id          uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                    field                   varchar(40)  NOT NULL,
                                    new_value               jsonb        NOT NULL,
                                    base_version_id         uuid,                           -- 그때 도장 찍힌 판(다형 참조라 FK 없음)
                                    repair_case_id          uuid,
                                    payment_id              uuid,
                                    inspection_item_id      uuid,
                                    settlement_transfer_id  uuid,  -- 넷 다 NULL이면 호실 기록의 입주일
                                    target_key              varchar(80)  NOT NULL,           -- 대상 종류 + ID
                                    retry_of_id             uuid,
                                    requested_by_side       varchar(10)  NOT NULL CHECK (requested_by_side IN ('TENANT', 'LANDLORD')),
                                    reason                  text,
                                    status                  varchar(20)  NOT NULL CHECK (status IN (
                                                                                                    'WAITING', 'ACCEPTED', 'REJECTED', 'UNCONFIRMED',
                                                                                                    'WITHDRAWN', 'SUPERSEDED', 'CLOSED')),
                                    closed_reason           varchar(30) CHECK (closed_reason IN (
                                        'SHARING_STOPPED', 'COUNTERPART_WITHDRAWN', 'SETTLED', 'CLOSED_BY_BOTH')),
                                    closed_at               timestamptz,
                                    created_at              timestamptz  NOT NULL,
                                    version                 bigint       NOT NULL,
                                    CHECK ((status = 'CLOSED') = (closed_reason IS NOT NULL)),
                                    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
                                    CHECK (num_nonnulls(repair_case_id, payment_id, inspection_item_id, settlement_transfer_id) <= 1)
);

-- =========================================================
-- 10. 사진 (05 2-8)
-- =========================================================
CREATE TABLE photo (
                       id                      uuid PRIMARY KEY,
                       room_record_id          uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                       repair_case_id          uuid,
                       inspection_item_id      uuid,
                       inspection_id           uuid,       -- 부위 사진(하자 아님)
                       payment_id              uuid,
                       cause_note_id           uuid,
                       settlement_item_id      uuid,
                       progress_note_id        uuid,
                       correction_request_id   uuid,  -- 다시 요청에 더한 근거
                       redacted_from_photo_id  uuid,            -- 가린 사본이면 지운 원본
                       room_part_id            uuid,
                       stage                   varchar(10)  NOT NULL CHECK (stage IN ('MOVE_IN', 'BEFORE', 'AFTER', 'MOVE_OUT', 'EVIDENCE', 'BASIS', 'PROGRESS')),
                       uploaded_by_side        varchar(10)  NOT NULL CHECK (uploaded_by_side IN ('TENANT', 'LANDLORD')),
                       device_taken_at         timestamptz,                           -- 참고값(06 PHO-1f)
                       original_key            varchar(300) NOT NULL UNIQUE,
                       thumb_key               varchar(300) NOT NULL,
                       original_sha256         varchar(64)  NOT NULL,
                       thumb_sha256            varchar(64)  NOT NULL,
                       size_bytes              bigint       NOT NULL CHECK (size_bytes > 0),
                       sort_order              int          NOT NULL DEFAULT 0,
                       created_at              timestamptz  NOT NULL,
                       CHECK (num_nonnulls(repair_case_id, inspection_item_id, inspection_id, payment_id,
                                           cause_note_id, settlement_item_id, progress_note_id, correction_request_id) = 1),
                       CHECK ((inspection_id IS NOT NULL) = (room_part_id IS NOT NULL)),
                       CHECK (redacted_from_photo_id <> id)
);

CREATE TABLE photo_visibility (
                                  photo_id        uuid PRIMARY KEY,
                                  room_record_id  uuid        NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                                  hidden_on_link  boolean     NOT NULL DEFAULT false,
                                  marked_wrong_at timestamptz,
                                  erased_at       timestamptz,                            -- 한 번 채우면 되돌리지 않음
                                  erase_reason    varchar(20) CHECK (erase_reason IN ('PRIVACY', 'WITHDRAWAL', 'THIRD_PARTY')),
                                  created_at      timestamptz NOT NULL,
                                  version         bigint      NOT NULL,
                                  CHECK ((erased_at IS NULL) = (erase_reason IS NULL))
);

-- =========================================================
-- 11. 통지 (05 2-9)
-- =========================================================
CREATE TABLE notice (
                        id                   uuid PRIMARY KEY,
                        room_record_id       uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                        response_request_id  uuid,
                        link_id              uuid,
                        recipient_party_id   uuid         NOT NULL,
                        kind                 varchar(20)  NOT NULL CHECK (kind IN ('INVITE', 'REQUEST', 'RENOTIFY', 'SETTLEMENT_INVITE', 'INFO')),
                        channel              varchar(10)  NOT NULL CHECK (channel IN ('SMS', 'IN_APP')),
                        status               varchar(10)  NOT NULL CHECK (status IN ('REQUESTED', 'SENT', 'DELIVERED', 'VIEWED', 'FAILED')),
                        sent_at              timestamptz,
                        delivered_at         timestamptz,
                        viewed_at            timestamptz,
                        failed_at            timestamptz,
                        provider_message_id  varchar(100),                      -- 모은 문자는 여러 통지가 같은 값
                        batch_lead_id        uuid,       -- 발송 시 대표 통지, 단독 발송이면 자기 자신
                        next_poll_at         timestamptz,
                        scheduled_for        timestamptz,                       -- 모으기 2분, 21~08시 보류, 한도 미룸
                        created_at           timestamptz  NOT NULL,
                        version              bigint       NOT NULL
);

-- =========================================================
-- 12. 행동 기록 (05 2-3, 원본, 덧붙이기만)
--     해시는 SHA-256(32바이트). 호실의 첫 행동은 prev_hash = 32바이트 0
--     content_digest는 당시 본 판의 내용·사진 지문을 묶은 SHA-256. 내용 대상이 없는 행동은 NULL
--     앱이 내용 지문을 행동 hash 입력에 포함한다. 체인 검증과 실제 내용 지문 검증은 둘 다 필요
--     대상 칸 0개 = 호실 기록 자체(이사함, 정산 끝내기)
-- =========================================================
CREATE TABLE action (
                        id                      uuid PRIMARY KEY,
                        room_record_id          uuid         NOT NULL REFERENCES room_record (id) ON DELETE CASCADE,
                        room_seq                bigint       NOT NULL CHECK (room_seq >= 1),
                        prev_hash               bytea        NOT NULL,
                        hash                    bytea        NOT NULL,
                        content_digest          bytea,                            -- 내용 원문을 복사하지 않고 당시 판에 연결
                        actor_member_id         uuid REFERENCES member (id),     -- 서버·링크·운영 행동은 NULL
                        actor_side              varchar(10) CHECK (actor_side IN ('TENANT', 'LANDLORD')),
                        actor_key               varchar(80)  NOT NULL,           -- 회원 ID / server / link:<ID> / ops:<담당>
                        type                    varchar(40)  NOT NULL,
                        idempotency_key         varchar(100) NOT NULL,           -- 한 요청의 여러 행동은 <키>#2 …
                        seen_version_no         int,                              -- 판의 no, CONFIRM_FINISH는 settlement.snapshot_no
                        reason                  text,
                        payload                 jsonb,                            -- 작은 부가 값만(while_open_objection 등, 이름·번호 금지)
                        party_id                uuid,
                        link_id                 uuid,
                        room_part_id            uuid,
                        notice_id               uuid,
                        repair_case_id          uuid,
                        payment_id              uuid,
                        inspection_id           uuid,
                        inspection_item_id      uuid,
                        settlement_id           uuid,
                        settlement_item_id      uuid,
                        settlement_transfer_id  uuid,
                        proposal_id             uuid,
                        response_request_id     uuid,
                        correction_request_id   uuid,
                        cause_note_id           uuid,
                        progress_note_id        uuid,
                        photo_id                uuid,
                        created_at              timestamptz  NOT NULL,
                        CONSTRAINT uq_action_idempotency UNIQUE (actor_key, idempotency_key),
                        CONSTRAINT uq_action_room_seq    UNIQUE (room_record_id, room_seq),
                        CONSTRAINT ck_action_hash_size   CHECK (octet_length(prev_hash) = 32 AND octet_length(hash) = 32),
                        CONSTRAINT ck_action_content_digest_size CHECK (content_digest IS NULL OR octet_length(content_digest) = 32),
                        CONSTRAINT ck_action_one_target  CHECK (num_nonnulls(
                                                                        party_id, link_id, room_part_id, notice_id, repair_case_id, payment_id, inspection_id,
                                                                        inspection_item_id, settlement_id, settlement_item_id, settlement_transfer_id, proposal_id,
                                                                        response_request_id, correction_request_id, cause_note_id, progress_note_id, photo_id) <= 1),
                        CONSTRAINT ck_action_type CHECK (type IN (
                            -- 호실·참여
                                                                  'CREATE_ROOM', 'EDIT_ROOM_INFO', 'ADD_PART', 'HIDE_PART', 'TOGGLE_PART',
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
                                                                  'HIDE_PHOTO', 'SHOW_PHOTO', 'MARK_PHOTO_WRONG', 'UNMARK_PHOTO_WRONG', 'ERASE_PHOTO'))
);

-- =========================================================
-- 13. 작업·알림·횟수·삭제 기록 (05 2-9)
-- =========================================================
CREATE TABLE outbox (
                        id               uuid PRIMARY KEY,
                        kind             varchar(20)  NOT NULL CHECK (kind IN ('SMS', 'S3_DELETE', 'AUDIT_WRITE', 'ORPHAN_CHECK')),
                        dedupe_key       varchar(300) UNIQUE,                    -- 고아 파일 확인은 orphan-check:<원본 키>
                        payload          jsonb        NOT NULL,                  -- 통지 ID, S3 키 등. 번호를 복사하지 않음
                        status           varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'DONE', 'FAILED')),
                        attempts         int          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                        next_attempt_at  timestamptz  NOT NULL,                  -- 21~08시 보류·한도 미룸도 이 칸으로
                        created_at       timestamptz  NOT NULL,
                        version          bigint       NOT NULL
);

CREATE TABLE in_app_notification (
                                     id                   uuid PRIMARY KEY,
                                     room_record_id       uuid REFERENCES room_record (id) ON DELETE CASCADE,  -- 서비스 안내(번호 인증 해제 등)는 NULL
                                     member_id            uuid        NOT NULL REFERENCES member (id),
                                     kind                 varchar(10) NOT NULL CHECK (kind IN ('RESPONSE', 'INFO', 'SERVICE')),
                                     response_request_id  uuid,
                                     target_kind          varchar(30),                        -- 이동할 대상(05 "이동할 대상")
                                     target_id            uuid,
                                     group_key            varchar(120),                       -- 하루 묶음, 예: erase-digest:<호실>:<날짜>
                                     group_count          int CHECK (group_count >= 1),
                                     read_at              timestamptz,
                                     expires_at           timestamptz NOT NULL,               -- 30일
                                     created_at           timestamptz NOT NULL,
                                     version              bigint      NOT NULL,
                                     CHECK ((target_kind IS NULL) = (target_id IS NULL))
);

ALTER TABLE in_app_notification ADD CONSTRAINT ck_in_app_request_room
    CHECK (response_request_id IS NULL OR room_record_id IS NOT NULL);

CREATE TABLE rate_counter (
                              counter_key  varchar(200) NOT NULL,   -- upload:<회원>, verify:<번호 HMAC>, sms:<번호 HMAC>, sms-member:<회원>, sms-total, rerequest:…
                              window_date  date         NOT NULL,
                              count        int          NOT NULL CHECK (count >= 0),
                              PRIMARY KEY (counter_key, window_date)
);

CREATE TABLE deletion_log (
                              id           uuid PRIMARY KEY,
                              target_kind  varchar(40)  NOT NULL,
                              target_id    uuid         NOT NULL,                      -- FK 없음(지운 대상)
                              reason       varchar(30)  NOT NULL,
                              phone_hmac   varchar(128),                               -- 수신 거부일 때
                              chain_room_id uuid,                                     -- FK 없음: 삭제된 행동의 호실, 호실 삭제 뒤에도 보존
                              room_seq     bigint CHECK (room_seq >= 1),
                              prev_hash    bytea CHECK (octet_length(prev_hash) = 32),
                              action_hash  bytea CHECK (octet_length(action_hash) = 32),
                              created_at   timestamptz  NOT NULL,
                              CHECK ((target_kind = 'ACTION' AND num_nonnulls(chain_room_id, room_seq, prev_hash, action_hash) = 4)
                                  OR (target_kind <> 'ACTION' AND num_nonnulls(chain_room_id, room_seq, prev_hash, action_hash) = 0))
);

-- =========================================================
-- 14. 뒤에서 만든 테이블을 가리키는 FK
--     머리 → 지금 판은 지연 검사(같은 트랜잭션에서 머리와 첫 판을 어느 순서로 넣어도 됨)
-- =========================================================
-- 호실이 다른 부모를 연결할 수 없도록 모든 호실 소속 참조에 복합 FK를 둔다.
ALTER TABLE cause_note ADD CONSTRAINT uq_cause_note_id_room UNIQUE (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT uq_correction_request_id_room UNIQUE (id, room_record_id);
ALTER TABLE inspection ADD CONSTRAINT uq_inspection_id_room UNIQUE (id, room_record_id);
ALTER TABLE inspection_item ADD CONSTRAINT uq_inspection_item_id_room UNIQUE (id, room_record_id);
ALTER TABLE inspection_item_version ADD CONSTRAINT uq_inspection_item_version_id_room UNIQUE (id, room_record_id);
ALTER TABLE link ADD CONSTRAINT uq_link_id_room UNIQUE (id, room_record_id);
ALTER TABLE notice ADD CONSTRAINT uq_notice_id_room UNIQUE (id, room_record_id);
ALTER TABLE party ADD CONSTRAINT uq_party_id_room UNIQUE (id, room_record_id);
ALTER TABLE payment ADD CONSTRAINT uq_payment_id_room UNIQUE (id, room_record_id);
ALTER TABLE payment_version ADD CONSTRAINT uq_payment_version_id_room UNIQUE (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT uq_photo_id_room UNIQUE (id, room_record_id);
ALTER TABLE progress_note ADD CONSTRAINT uq_progress_note_id_room UNIQUE (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT uq_proposal_id_room UNIQUE (id, room_record_id);
ALTER TABLE repair_case ADD CONSTRAINT uq_repair_case_id_room UNIQUE (id, room_record_id);
ALTER TABLE repair_content ADD CONSTRAINT uq_repair_content_id_room UNIQUE (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT uq_response_request_id_room UNIQUE (id, room_record_id);
ALTER TABLE room_part ADD CONSTRAINT uq_room_part_id_room UNIQUE (id, room_record_id);
ALTER TABLE settlement ADD CONSTRAINT uq_settlement_id_room UNIQUE (id, room_record_id);
ALTER TABLE settlement_item ADD CONSTRAINT uq_settlement_item_id_room UNIQUE (id, room_record_id);
ALTER TABLE settlement_transfer ADD CONSTRAINT uq_settlement_transfer_id_room UNIQUE (id, room_record_id);
ALTER TABLE settlement_transfer_version ADD CONSTRAINT uq_settlement_transfer_version_id_room UNIQUE (id, room_record_id);

ALTER TABLE repair_case ADD CONSTRAINT fk_repair_case_previous_case_id_room FOREIGN KEY (previous_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE repair_case ADD CONSTRAINT fk_repair_case_related_case_id_room FOREIGN KEY (related_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE repair_content ADD CONSTRAINT fk_repair_content_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE repair_content ADD CONSTRAINT fk_repair_content_room_part_id_room FOREIGN KEY (room_part_id, room_record_id) REFERENCES room_part (id, room_record_id);
ALTER TABLE repair_content ADD CONSTRAINT fk_repair_content_previous_id_room FOREIGN KEY (previous_id, room_record_id) REFERENCES repair_content (id, room_record_id);
ALTER TABLE inspection_item ADD CONSTRAINT fk_inspection_item_inspection_id_room FOREIGN KEY (inspection_id, room_record_id) REFERENCES inspection (id, room_record_id);
ALTER TABLE inspection_item_version ADD CONSTRAINT fk_inspection_item_version_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES inspection_item (id, room_record_id);
ALTER TABLE inspection_item_version ADD CONSTRAINT fk_inspection_item_version_room_part_id_room FOREIGN KEY (room_part_id, room_record_id) REFERENCES room_part (id, room_record_id);
ALTER TABLE inspection_item_version ADD CONSTRAINT fk_inspection_item_version_previous_id_room FOREIGN KEY (previous_id, room_record_id) REFERENCES inspection_item_version (id, room_record_id);
ALTER TABLE settlement_item ADD CONSTRAINT fk_settlement_item_settlement_id_room FOREIGN KEY (settlement_id, room_record_id) REFERENCES settlement (id, room_record_id);
ALTER TABLE settlement_item ADD CONSTRAINT fk_settlement_item_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE settlement_item ADD CONSTRAINT fk_settlement_item_room_part_id_room FOREIGN KEY (room_part_id, room_record_id) REFERENCES room_part (id, room_record_id);
ALTER TABLE settlement_transfer ADD CONSTRAINT fk_settlement_transfer_settlement_id_room FOREIGN KEY (settlement_id, room_record_id) REFERENCES settlement (id, room_record_id);
ALTER TABLE settlement_transfer ADD CONSTRAINT fk_settlement_transfer_same_as_transfer_id_room FOREIGN KEY (same_as_transfer_id, room_record_id) REFERENCES settlement_transfer (id, room_record_id);
ALTER TABLE settlement_transfer_version ADD CONSTRAINT fk_settlement_transfer_version_settlement_transfer_id_room FOREIGN KEY (settlement_transfer_id, room_record_id) REFERENCES settlement_transfer (id, room_record_id);
ALTER TABLE settlement_transfer_version ADD CONSTRAINT fk_settlement_transfer_version_previous_id_room FOREIGN KEY (previous_id, room_record_id) REFERENCES settlement_transfer_version (id, room_record_id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE payment_version ADD CONSTRAINT fk_payment_version_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES payment (id, room_record_id);
ALTER TABLE payment_version ADD CONSTRAINT fk_payment_version_previous_id_room FOREIGN KEY (previous_id, room_record_id) REFERENCES payment_version (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT fk_proposal_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT fk_proposal_settlement_item_id_room FOREIGN KEY (settlement_item_id, room_record_id) REFERENCES settlement_item (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT fk_proposal_settlement_id_room FOREIGN KEY (settlement_id, room_record_id) REFERENCES settlement (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT fk_proposal_counter_of_id_room FOREIGN KEY (counter_of_id, room_record_id) REFERENCES proposal (id, room_record_id);
ALTER TABLE proposal ADD CONSTRAINT fk_proposal_replaces_id_room FOREIGN KEY (replaces_id, room_record_id) REFERENCES proposal (id, room_record_id);
ALTER TABLE proposal_line ADD CONSTRAINT fk_proposal_line_proposal_id_room FOREIGN KEY (proposal_id, room_record_id) REFERENCES proposal (id, room_record_id);
ALTER TABLE link ADD CONSTRAINT fk_link_party_id_room FOREIGN KEY (party_id, room_record_id) REFERENCES party (id, room_record_id);
ALTER TABLE link ADD CONSTRAINT fk_link_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE link ADD CONSTRAINT fk_link_inspection_id_room FOREIGN KEY (inspection_id, room_record_id) REFERENCES inspection (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_progress_note_id_room FOREIGN KEY (progress_note_id, room_record_id) REFERENCES progress_note (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES payment (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_settlement_item_id_room FOREIGN KEY (settlement_item_id, room_record_id) REFERENCES settlement_item (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_settlement_id_room FOREIGN KEY (settlement_id, room_record_id) REFERENCES settlement (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_inspection_id_room FOREIGN KEY (inspection_id, room_record_id) REFERENCES inspection (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES inspection_item (id, room_record_id);
ALTER TABLE response_request ADD CONSTRAINT fk_response_request_settlement_transfer_id_room FOREIGN KEY (settlement_transfer_id, room_record_id) REFERENCES settlement_transfer (id, room_record_id);
ALTER TABLE cause_note ADD CONSTRAINT fk_cause_note_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE cause_note ADD CONSTRAINT fk_cause_note_previous_id_room FOREIGN KEY (previous_id, room_record_id) REFERENCES cause_note (id, room_record_id);
ALTER TABLE progress_note ADD CONSTRAINT fk_progress_note_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE progress_note ADD CONSTRAINT fk_progress_note_answers_request_id_room FOREIGN KEY (answers_request_id, room_record_id) REFERENCES response_request (id, room_record_id);
ALTER TABLE progress_note_response ADD CONSTRAINT fk_progress_note_response_progress_note_id_room FOREIGN KEY (progress_note_id, room_record_id) REFERENCES progress_note (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT fk_correction_request_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT fk_correction_request_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES payment (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT fk_correction_request_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES inspection_item (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT fk_correction_request_settlement_transfer_id_room FOREIGN KEY (settlement_transfer_id, room_record_id) REFERENCES settlement_transfer (id, room_record_id);
ALTER TABLE correction_request ADD CONSTRAINT fk_correction_request_retry_of_id_room FOREIGN KEY (retry_of_id, room_record_id) REFERENCES correction_request (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES inspection_item (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_inspection_id_room FOREIGN KEY (inspection_id, room_record_id) REFERENCES inspection (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES payment (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_cause_note_id_room FOREIGN KEY (cause_note_id, room_record_id) REFERENCES cause_note (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_settlement_item_id_room FOREIGN KEY (settlement_item_id, room_record_id) REFERENCES settlement_item (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_progress_note_id_room FOREIGN KEY (progress_note_id, room_record_id) REFERENCES progress_note (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_correction_request_id_room FOREIGN KEY (correction_request_id, room_record_id) REFERENCES correction_request (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_redacted_from_photo_id_room FOREIGN KEY (redacted_from_photo_id, room_record_id) REFERENCES photo (id, room_record_id);
ALTER TABLE photo ADD CONSTRAINT fk_photo_room_part_id_room FOREIGN KEY (room_part_id, room_record_id) REFERENCES room_part (id, room_record_id);
ALTER TABLE photo_visibility ADD CONSTRAINT fk_photo_visibility_photo_id_room FOREIGN KEY (photo_id, room_record_id) REFERENCES photo (id, room_record_id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_response_request_id_room FOREIGN KEY (response_request_id, room_record_id) REFERENCES response_request (id, room_record_id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_link_id_room FOREIGN KEY (link_id, room_record_id) REFERENCES link (id, room_record_id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_recipient_party_id_room FOREIGN KEY (recipient_party_id, room_record_id) REFERENCES party (id, room_record_id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_batch_lead_id_room FOREIGN KEY (batch_lead_id, room_record_id) REFERENCES notice (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_party_id_room FOREIGN KEY (party_id, room_record_id) REFERENCES party (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_link_id_room FOREIGN KEY (link_id, room_record_id) REFERENCES link (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_room_part_id_room FOREIGN KEY (room_part_id, room_record_id) REFERENCES room_part (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_notice_id_room FOREIGN KEY (notice_id, room_record_id) REFERENCES notice (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES repair_case (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES payment (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_inspection_id_room FOREIGN KEY (inspection_id, room_record_id) REFERENCES inspection (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES inspection_item (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_settlement_id_room FOREIGN KEY (settlement_id, room_record_id) REFERENCES settlement (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_settlement_item_id_room FOREIGN KEY (settlement_item_id, room_record_id) REFERENCES settlement_item (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_settlement_transfer_id_room FOREIGN KEY (settlement_transfer_id, room_record_id) REFERENCES settlement_transfer (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_proposal_id_room FOREIGN KEY (proposal_id, room_record_id) REFERENCES proposal (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_response_request_id_room FOREIGN KEY (response_request_id, room_record_id) REFERENCES response_request (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_correction_request_id_room FOREIGN KEY (correction_request_id, room_record_id) REFERENCES correction_request (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_cause_note_id_room FOREIGN KEY (cause_note_id, room_record_id) REFERENCES cause_note (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_progress_note_id_room FOREIGN KEY (progress_note_id, room_record_id) REFERENCES progress_note (id, room_record_id);
ALTER TABLE action ADD CONSTRAINT fk_action_photo_id_room FOREIGN KEY (photo_id, room_record_id) REFERENCES photo (id, room_record_id);
ALTER TABLE in_app_notification ADD CONSTRAINT fk_in_app_notification_response_request_id_room FOREIGN KEY (response_request_id, room_record_id) REFERENCES response_request (id, room_record_id);

ALTER TABLE repair_case
    ADD CONSTRAINT fk_repair_case_current_content
        FOREIGN KEY (current_content_id, room_record_id) REFERENCES repair_content (id, room_record_id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_repair_case_confirmed_content
    FOREIGN KEY (confirmed_content_id, room_record_id) REFERENCES repair_content (id, room_record_id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_repair_case_current_proposal
    FOREIGN KEY (current_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id),
  ADD CONSTRAINT fk_repair_case_agreed_proposal
    FOREIGN KEY (agreed_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id);

ALTER TABLE inspection_item
    ADD CONSTRAINT fk_inspection_item_current_version
        FOREIGN KEY (current_version_id, room_record_id) REFERENCES inspection_item_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_inspection_item_confirmed_version
    FOREIGN KEY (confirmed_version_id, room_record_id) REFERENCES inspection_item_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE settlement
    ADD CONSTRAINT fk_settlement_current_deposit_proposal
        FOREIGN KEY (current_deposit_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id),
  ADD CONSTRAINT fk_settlement_agreed_deposit_proposal
    FOREIGN KEY (agreed_deposit_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id);

ALTER TABLE settlement_item
    ADD CONSTRAINT fk_settlement_item_current_proposal
        FOREIGN KEY (current_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id),
  ADD CONSTRAINT fk_settlement_item_agreed_proposal
    FOREIGN KEY (agreed_proposal_id, room_record_id) REFERENCES proposal (id, room_record_id);

ALTER TABLE settlement_transfer
    ADD CONSTRAINT fk_settlement_transfer_current_version
        FOREIGN KEY (current_version_id, room_record_id) REFERENCES settlement_transfer_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_settlement_transfer_confirmed_version
    FOREIGN KEY (confirmed_version_id, room_record_id) REFERENCES settlement_transfer_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_current_version
        FOREIGN KEY (current_version_id, room_record_id) REFERENCES payment_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_payment_confirmed_version
    FOREIGN KEY (confirmed_version_id, room_record_id) REFERENCES payment_version (id, room_record_id) DEFERRABLE INITIALLY DEFERRED;

-- =========================================================
-- 15. 인덱스 (05에 적힌 것 + 작업자·조회용)
-- =========================================================
-- 계정
CREATE UNIQUE INDEX ux_member_email_lower ON member (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX ux_member_phone ON member (phone) WHERE phone IS NOT NULL;   -- 불변식 8
CREATE INDEX ix_refresh_token_member ON refresh_token (member_id);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
CREATE INDEX ix_phone_verification_phone ON phone_verification (phone, created_at);
CREATE INDEX ix_member_acknowledgement_member ON member_acknowledgement (member_id);

-- 호실과 참여
CREATE INDEX ix_region_dong ON region (dong);
CREATE INDEX ix_room_record_autoclose ON room_record (autoclose_due_at) WHERE autoclose_due_at IS NOT NULL;
CREATE INDEX ix_room_record_delete ON room_record (delete_due_at) WHERE delete_due_at IS NOT NULL;
CREATE INDEX ix_room_record_inactivity ON room_record (inactivity_notice_due_at) WHERE inactivity_notice_due_at IS NOT NULL;
CREATE INDEX ix_party_member ON party (member_id) WHERE member_id IS NOT NULL;
CREATE INDEX ix_party_invite_phone ON party (invite_phone) WHERE invite_phone IS NOT NULL;  -- GET /api/invitations
CREATE INDEX ix_room_part_room ON room_part (room_record_id);
CREATE INDEX ix_link_party ON link (party_id);

-- 수리와 돈
CREATE INDEX ix_repair_case_room ON repair_case (room_record_id);
CREATE INDEX ix_repair_content_room_part ON repair_content (room_part_id);
CREATE INDEX ix_cause_note_repair_case ON cause_note (repair_case_id);
CREATE INDEX ix_progress_note_repair_case ON progress_note (repair_case_id);
CREATE INDEX ix_payment_repair_case ON payment (repair_case_id);
CREATE INDEX ix_proposal_repair_case ON proposal (repair_case_id) WHERE repair_case_id IS NOT NULL;
CREATE INDEX ix_proposal_settlement_item ON proposal (settlement_item_id) WHERE settlement_item_id IS NOT NULL;
CREATE INDEX ix_proposal_settlement ON proposal (settlement_id) WHERE settlement_id IS NOT NULL;
CREATE INDEX ix_proposal_line_proposal ON proposal_line (proposal_id);
CREATE UNIQUE INDEX ux_correction_waiting ON correction_request (target_key, field) WHERE status = 'WAITING';

-- 응답 요청: 작업자는 이 테이블만 본다
CREATE INDEX ix_response_request_renotify ON response_request (renotify_due_at) WHERE status = 'WAITING';
CREATE INDEX ix_response_request_unconfirmed ON response_request (unconfirmed_due_at) WHERE status = 'WAITING';
CREATE INDEX ix_response_request_room ON response_request (room_record_id);
CREATE UNIQUE INDEX ux_proposal_waiting
    ON response_request (COALESCE(repair_case_id, settlement_item_id, settlement_id))
    WHERE kind = 'PROPOSAL' AND status IN ('WAITING', 'UNCONFIRMED');
-- 대상 종류별 NULL도 같은 값으로 비교해 같은 호실·같은 종류·같은 대상 WAITING은 하나만 둔다.
CREATE UNIQUE INDEX ux_response_request_waiting
    ON response_request (room_record_id, kind, repair_case_id, payment_id, settlement_item_id,
                         settlement_id, inspection_id, inspection_item_id, settlement_transfer_id, progress_note_id)
    NULLS NOT DISTINCT
    WHERE status = 'WAITING' AND kind NOT IN ('PROPOSAL', 'INSPECTION_CONFIRM');
CREATE UNIQUE INDEX ux_inspection_confirm_waiting
    ON response_request (inspection_id, requested_by_side)
    WHERE kind = 'INSPECTION_CONFIRM' AND status = 'WAITING';                        -- 점검 × 방향마다 하나(불변식 21)

-- 점검·정산
CREATE INDEX ix_inspection_item_inspection ON inspection_item (inspection_id);
CREATE INDEX ix_inspection_item_version_room_part ON inspection_item_version (room_part_id);
CREATE UNIQUE INDEX ux_settlement_item_active_repair
    ON settlement_item (settlement_id, repair_case_id)
    WHERE source = 'REPAIR' AND status <> 'EXCLUDED';
CREATE INDEX ix_settlement_item_settlement ON settlement_item (settlement_id);
CREATE INDEX ix_settlement_transfer_settlement ON settlement_transfer (settlement_id);

-- 사진
CREATE INDEX ix_photo_room_part_stage ON photo (room_record_id, room_part_id, stage)
    WHERE room_part_id IS NOT NULL;                                                   -- 나란히 보기(부위 사진만 부위 칸이 있음)
CREATE INDEX ix_photo_repair_case ON photo (repair_case_id) WHERE repair_case_id IS NOT NULL;
CREATE INDEX ix_photo_inspection_item ON photo (inspection_item_id) WHERE inspection_item_id IS NOT NULL;
CREATE INDEX ix_photo_inspection ON photo (inspection_id) WHERE inspection_id IS NOT NULL;
CREATE INDEX ix_photo_payment ON photo (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX ix_photo_cause_note ON photo (cause_note_id) WHERE cause_note_id IS NOT NULL;
CREATE INDEX ix_photo_settlement_item ON photo (settlement_item_id) WHERE settlement_item_id IS NOT NULL;
CREATE INDEX ix_photo_progress_note ON photo (progress_note_id) WHERE progress_note_id IS NOT NULL;
CREATE INDEX ix_photo_correction_request ON photo (correction_request_id) WHERE correction_request_id IS NOT NULL;

-- 행동 기록: 대상 칸마다 부분 인덱스
CREATE INDEX ix_action_room_created ON action (room_record_id, created_at);       -- 경과, 마지막 당사자 행동
CREATE INDEX ix_action_party ON action (party_id) WHERE party_id IS NOT NULL;
CREATE INDEX ix_action_link ON action (link_id) WHERE link_id IS NOT NULL;
CREATE INDEX ix_action_room_part ON action (room_part_id) WHERE room_part_id IS NOT NULL;
CREATE INDEX ix_action_notice ON action (notice_id) WHERE notice_id IS NOT NULL;
CREATE INDEX ix_action_repair_case ON action (repair_case_id) WHERE repair_case_id IS NOT NULL;
CREATE INDEX ix_action_payment ON action (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX ix_action_inspection ON action (inspection_id) WHERE inspection_id IS NOT NULL;
CREATE INDEX ix_action_inspection_item ON action (inspection_item_id) WHERE inspection_item_id IS NOT NULL;
CREATE INDEX ix_action_settlement ON action (settlement_id) WHERE settlement_id IS NOT NULL;
CREATE INDEX ix_action_settlement_item ON action (settlement_item_id) WHERE settlement_item_id IS NOT NULL;
CREATE INDEX ix_action_settlement_transfer ON action (settlement_transfer_id) WHERE settlement_transfer_id IS NOT NULL;
CREATE INDEX ix_action_proposal ON action (proposal_id) WHERE proposal_id IS NOT NULL;
CREATE INDEX ix_action_response_request ON action (response_request_id) WHERE response_request_id IS NOT NULL;
CREATE INDEX ix_action_correction_request ON action (correction_request_id) WHERE correction_request_id IS NOT NULL;
CREATE INDEX ix_action_cause_note ON action (cause_note_id) WHERE cause_note_id IS NOT NULL;
CREATE INDEX ix_action_progress_note ON action (progress_note_id) WHERE progress_note_id IS NOT NULL;
CREATE INDEX ix_action_photo ON action (photo_id) WHERE photo_id IS NOT NULL;

-- 통지·작업·알림
CREATE INDEX ix_notice_response_request ON notice (response_request_id);
CREATE INDEX ix_notice_poll ON notice (next_poll_at) WHERE status = 'SENT';
CREATE INDEX ix_outbox_pending ON outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_in_app_group ON in_app_notification (member_id, group_key) WHERE group_key IS NOT NULL;
CREATE INDEX ix_in_app_member ON in_app_notification (member_id, created_at);
CREATE INDEX ix_in_app_expires ON in_app_notification (expires_at);
CREATE INDEX ix_rate_counter_window ON rate_counter (window_date);
CREATE UNIQUE INDEX ux_deletion_log_chain ON deletion_log (chain_room_id, room_seq) WHERE target_kind = 'ACTION';

-- =========================================================
-- 16. 트리거 연결 ([A] action, [B] 사실 행)
-- =========================================================
CREATE TRIGGER trg_action_append_only                      BEFORE UPDATE OR DELETE ON action                      FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_member_acknowledgement_append_only      BEFORE UPDATE OR DELETE ON member_acknowledgement      FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_sms_opt_out_append_only                 BEFORE UPDATE OR DELETE ON sms_opt_out                 FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_repair_content_append_only              BEFORE UPDATE OR DELETE ON repair_content              FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_cause_note_append_only                  BEFORE UPDATE OR DELETE ON cause_note                  FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_progress_note_append_only               BEFORE UPDATE OR DELETE ON progress_note               FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_payment_version_append_only             BEFORE UPDATE OR DELETE ON payment_version             FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_proposal_append_only                    BEFORE UPDATE OR DELETE ON proposal                    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_proposal_line_append_only               BEFORE UPDATE OR DELETE ON proposal_line               FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_inspection_item_version_append_only     BEFORE UPDATE OR DELETE ON inspection_item_version     FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_settlement_transfer_version_append_only BEFORE UPDATE OR DELETE ON settlement_transfer_version FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_photo_append_only                       BEFORE UPDATE OR DELETE ON photo                       FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_deletion_log_append_only                BEFORE UPDATE OR DELETE ON deletion_log                FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_link_invalidated_once                   BEFORE UPDATE ON link                                  FOR EACH ROW EXECUTE FUNCTION forbid_link_revalidate();

-- 행 트리거로 막을 수 없는 TRUNCATE는 보관 삭제 경로에서도 항상 거절한다.
CREATE TRIGGER trg_action_no_truncate BEFORE TRUNCATE ON action FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_member_acknowledgement_no_truncate BEFORE TRUNCATE ON member_acknowledgement FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_sms_opt_out_no_truncate BEFORE TRUNCATE ON sms_opt_out FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_repair_content_no_truncate BEFORE TRUNCATE ON repair_content FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_cause_note_no_truncate BEFORE TRUNCATE ON cause_note FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_progress_note_no_truncate BEFORE TRUNCATE ON progress_note FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_payment_version_no_truncate BEFORE TRUNCATE ON payment_version FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_proposal_no_truncate BEFORE TRUNCATE ON proposal FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_proposal_line_no_truncate BEFORE TRUNCATE ON proposal_line FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_inspection_item_version_no_truncate BEFORE TRUNCATE ON inspection_item_version FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_settlement_transfer_version_no_truncate BEFORE TRUNCATE ON settlement_transfer_version FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_photo_no_truncate BEFORE TRUNCATE ON photo FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_deletion_log_no_truncate BEFORE TRUNCATE ON deletion_log FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
