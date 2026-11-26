package com.chilsuitjo.back.proposal.repository;

import com.chilsuitjo.back.proposal.entity.Proposal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProposalRepository extends JpaRepository<Proposal, UUID> {

  /** 그 호실의 제안. 다른 호실의 ID면 비어 있다(남의 기록은 있는지도 알 수 없게, ROOM-2a). */
  Optional<Proposal> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /**
   * 이 수리 건 부담 안건의 마지막 회차. 제안이 없으면 0. 회차는 head에 두지 않고 제안 행에서 센다({@code AgendaState} 주석). 호실을 잠근 뒤에
   * 부르므로 다음 회차는 겹치지 않는다.
   */
  @Query(
      "select coalesce(max(p.round), 0) from Proposal p"
          + " where p.repairCaseId = :repairCaseId and p.roomRecordId = :roomRecordId")
  int maxRoundOfRepairCase(
      @Param("roomRecordId") UUID roomRecordId, @Param("repairCaseId") UUID repairCaseId);
}
