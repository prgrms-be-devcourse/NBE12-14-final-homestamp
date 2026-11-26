-- 보충 기록(record.supplement, B 테이블, #155).
-- [보충하고 다시 확인 요청](03 G "응답과 그 뒤", 06 REP-2e)은 보류·이견이 달린 기록에 기록한 측이 사진·설명을 덧붙이고
-- 다시 확인을 요청하는 일이다. 사진은 그 행동의 payload "photos"로 남지만(#141) 설명을 담을 곳이 없었다.
-- 수리 건 하자(SUPPLEMENT), 지불(SUPPLEMENT_PAYMENT), 점검 항목(SUPPLEMENT_ITEM)이 같은 테이블을 쓴다.
--
-- 덧붙이기만 한다(원칙 1): 고치기·지우기는 트리거가 거절한다. 호실 기록을 통째로 지울 때만 함께 지워진다(ON DELETE CASCADE).
-- 설명이 없는 보충(사진만)도 있어서 description은 NULL을 받는다. 근거(사진이나 설명 하나 이상)는 서비스가 본다.

CREATE TABLE record.supplement (
                                 id                  uuid PRIMARY KEY,
                                 room_record_id      uuid        NOT NULL REFERENCES state.room_record (id) ON DELETE CASCADE,
                                 repair_case_id      uuid,
                                 payment_id          uuid,
                                 inspection_item_id  uuid,
                                 side                varchar(10) NOT NULL CHECK (side IN ('TENANT', 'LANDLORD')),
                                 description         text,
                                 created_at          timestamptz NOT NULL,
                                 CONSTRAINT ck_supplement_one_target
                                     CHECK (num_nonnulls(repair_case_id, payment_id, inspection_item_id) = 1)
);

-- 다른 호실의 기록을 가리키지 못하게 대상 칸마다 (대상, 호실) 복합 FK를 둔다(05 0장, V1과 같은 방식).
ALTER TABLE record.supplement ADD CONSTRAINT fk_supplement_repair_case_id_room FOREIGN KEY (repair_case_id, room_record_id) REFERENCES state.repair_case (id, room_record_id);
ALTER TABLE record.supplement ADD CONSTRAINT fk_supplement_payment_id_room FOREIGN KEY (payment_id, room_record_id) REFERENCES state.payment (id, room_record_id);
ALTER TABLE record.supplement ADD CONSTRAINT fk_supplement_inspection_item_id_room FOREIGN KEY (inspection_item_id, room_record_id) REFERENCES state.inspection_item (id, room_record_id);

CREATE INDEX ix_supplement_repair_case ON record.supplement (repair_case_id) WHERE repair_case_id IS NOT NULL;
CREATE INDEX ix_supplement_payment ON record.supplement (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX ix_supplement_inspection_item ON record.supplement (inspection_item_id) WHERE inspection_item_id IS NOT NULL;

CREATE TRIGGER trg_supplement_append_only BEFORE UPDATE OR DELETE ON record.supplement FOR EACH ROW EXECUTE FUNCTION record.forbid_change();
CREATE TRIGGER trg_supplement_no_truncate BEFORE TRUNCATE ON record.supplement FOR EACH STATEMENT EXECUTE FUNCTION record.forbid_change();
