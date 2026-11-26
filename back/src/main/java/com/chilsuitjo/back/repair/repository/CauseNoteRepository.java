package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.CauseNote;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 원인 설명({@code record.cause_note}). B 테이블이라 만들기만 하고 고치지 않는다(05 0장). */
public interface CauseNoteRepository extends JpaRepository<CauseNote, UUID> {

  Optional<CauseNote> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 한 호실의 설명 모두. 목록에서 설명 대상 행동이 어느 수리 건의 것인지 찾는다(06 4-6 {@code lastActionAt}). */
  List<CauseNote> findByRoomRecordId(UUID roomRecordId);

  /** 한 수리 건의 설명 모두(이전 설명 포함), 남긴 순서. */
  List<CauseNote> findByRepairCaseIdOrderByCreatedAtAscIdAsc(UUID repairCaseId);

  /** 이 설명을 고친 다음 수정본이 있나(V1 {@code ux_cause_note_previous}로 많아야 하나). */
  boolean existsByPreviousId(UUID previousId);
}
