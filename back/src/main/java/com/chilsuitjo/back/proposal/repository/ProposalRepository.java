package com.chilsuitjo.back.proposal.repository;

import com.chilsuitjo.back.proposal.entity.Proposal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProposalRepository extends JpaRepository<Proposal, UUID> {

  /** 그 호실의 제안. 다른 호실의 ID면 비어 있다(남의 기록은 있는지도 알 수 없게, ROOM-2a). */
  Optional<Proposal> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);
}
