-- 행동 체인 머리(state.action_chain_head, C 테이블, #128·#244).
-- 행동의 다음 번호·이전 해시를 남은 마지막 행동에서 정하면, 탈퇴로 그 호실의 마지막 행동이 지워졌을 때(05 2-10)
-- 같은 room_seq를 다시 쓰고 deletion_log와 겹친다. 그래서 호실마다 "지금까지 쓴 가장 큰 번호와 그 행동의 해시"를 한 행에 둔다.
--
-- 행동 기록 저장(JpaActionWriter)만 읽고 쓴다: 호실 잠금 뒤 이 행을 FOR UPDATE로 읽어 room_seq = last_seq + 1,
-- prev_hash = last_hash로 쓰고 같은 트랜잭션에서 두 칸을 올린다. 첫 행동이 행을 만든다.
-- 탈퇴 부분 삭제는 이 행을 건드리지 않는다. 호실 통째 삭제 때만 함께 지워진다(ON DELETE CASCADE).
-- room_record에 칸을 두지 않은 것은 행동마다 호실 행을 고치면 RoomRecord 엔티티의 @Version·전체 칸 UPDATE와 부딪혀서다(05 2-3).

CREATE TABLE state.action_chain_head (
                                       id              uuid PRIMARY KEY,
                                       room_record_id  uuid        NOT NULL REFERENCES state.room_record (id) ON DELETE CASCADE,
                                       last_seq        bigint      NOT NULL CHECK (last_seq >= 1),
                                       last_hash       bytea       NOT NULL CHECK (octet_length(last_hash) = 32),
                                       created_at      timestamptz NOT NULL,
                                       version         bigint      NOT NULL,
                                       CONSTRAINT uq_action_chain_head_room UNIQUE (room_record_id)
);

-- 이미 행동이 있는 호실은 남은 마지막 행동으로 채운다. 이 V 파일 전에는 부분 삭제 경로가 없어(05 0장 "아직 구현하지 않았다")
-- 남은 마지막 행동이 곧 지금까지 쓴 가장 큰 번호다. id는 V1 탈퇴 함수와 같이 DB의 uuidv7()로 만든다.
-- created_at은 앱이 머리를 만드는 때(그 호실의 첫 행동)와 같게 첫 행동(room_seq 1) 시각으로 둔다.
INSERT INTO state.action_chain_head (id, room_record_id, last_seq, last_hash, created_at, version)
SELECT pg_catalog.uuidv7(), a.room_record_id, a.room_seq, a.hash,
       (SELECT min(c.created_at) FROM record.action c WHERE c.room_record_id = a.room_record_id), 0
FROM record.action a
WHERE a.room_seq = (SELECT max(b.room_seq) FROM record.action b WHERE b.room_record_id = a.room_record_id);
