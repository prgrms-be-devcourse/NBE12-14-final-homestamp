package com.chilsuitjo.back.proposal.repository;

import com.chilsuitjo.back.proposal.entity.ProposalLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProposalLineRepository extends JpaRepository<ProposalLine, UUID> {

  /** 이 제안의 줄 금액 합. {@code proposal}에는 합계 칸이 없다(05 2-4). 줄이 없으면(비용 없음 안) 0. */
  @Query("select coalesce(sum(l.amount), 0L) from ProposalLine l where l.proposalId = :proposalId")
  long sumAmount(@Param("proposalId") UUID proposalId);
}
