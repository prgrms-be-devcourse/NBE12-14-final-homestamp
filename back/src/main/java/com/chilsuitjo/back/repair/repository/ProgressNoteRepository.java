package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.ProgressNote;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 진행 상황({@code record.progress_note}, B). 덧붙이기만 한다. repair 안에서만 쓴다. */
public interface ProgressNoteRepository extends JpaRepository<ProgressNote, UUID> {

  /** 이 수리 건들의 진행 상황 모두. 목록 한 쪽에서 진행 상황 대상 행동을 찾는다(06 4-6 {@code lastActionAt}). */
  List<ProgressNote> findByRepairCaseIdIn(Collection<UUID> repairCaseIds);

  /** 수리 건의 진행 상황, 남긴 순서(수리 건 상세 {@code progressNotes}). */
  List<ProgressNote> findByRepairCaseIdOrderByCreatedAtAscIdAsc(UUID repairCaseId);

  /** 그 호실의 진행 상황. 다른 호실의 ID면 비어 있다(ROOM-2a). */
  Optional<ProgressNote> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 그 수리 건의 진행 상황 ID들. 수리 건 카드 사진 한도를 셀 때 쓴다(06 0-1 {@code photo.max.repair}). */
  @Query("select n.id from ProgressNote n where n.repairCaseId = :repairCaseId")
  List<UUID> findIdsByRepairCaseId(@Param("repairCaseId") UUID repairCaseId);

  /**
   * 그 {@code NOTE_PROGRESS} 행동이 남긴 진행 상황. 행동의 대상 칸은 수리 건이라(상태를 읽는 대상) 진행 상황 ID가 행동에 없다. 둘은 같은 저장에서
   * 같은 시각·측으로 남으므로 그것으로 찾는다(같은 키로 다시 온 요청에 처음 만든 ID를 돌려줄 때, 06 4-0).
   */
  @Query(
      value =
          """
          SELECT n.id FROM record.progress_note n
          JOIN record.action a
            ON a.repair_case_id = n.repair_case_id
           AND a.created_at = n.created_at
           AND a.actor_side = n.side
          WHERE a.id = :actionId AND a.type = 'NOTE_PROGRESS'
          """,
      nativeQuery = true)
  Optional<UUID> findIdByAction(@Param("actionId") UUID actionId);
}
