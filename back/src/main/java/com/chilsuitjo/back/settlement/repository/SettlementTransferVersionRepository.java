package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.settlement.entity.SettlementTransferVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 정산 송금 판({@code record.settlement_transfer_version}). settlement 안에서만 쓴다. */
public interface SettlementTransferVersionRepository
    extends JpaRepository<SettlementTransferVersion, UUID> {

  /** 송금의 이 번호 판. 응답이 본 판의 지문을 만들 때 쓴다. */
  Optional<SettlementTransferVersion> findBySettlementTransferIdAndNo(UUID transferId, int no);
}
