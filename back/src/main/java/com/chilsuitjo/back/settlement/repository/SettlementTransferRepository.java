package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.settlement.entity.SettlementTransfer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 정산 송금 머리({@code state.settlement_transfer}). settlement 안에서만 쓴다. */
public interface SettlementTransferRepository extends JpaRepository<SettlementTransfer, UUID> {

  /** 그 호실의 송금. 다른 호실의 ID면 비어 있다(ROOM-2a). */
  Optional<SettlementTransfer> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 정산의 송금, 기록한 순서대로. */
  List<SettlementTransfer> findBySettlementIdOrderByCreatedAtAscIdAsc(UUID settlementId);

  /** 송금 ID만 받는 API가 잠글 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에 읽어도 된다. */
  @Query("select t.roomRecordId from SettlementTransfer t where t.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);
}
