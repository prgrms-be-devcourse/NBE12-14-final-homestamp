package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.settlement.entity.Settlement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 정산({@code state.settlement}). settlement 안에서만 쓴다(09 2장 {@code repository/}). */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

  /** 그 호실의 정산(호실 기록마다 하나, {@code UNIQUE(room_record_id)}). */
  Optional<Settlement> findByRoomRecordId(UUID roomRecordId);

  /**
   * 정산 ID만 받는 API(06 4-8 {@code /api/settlements/{id}/...})가 볼 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에 읽어도
   * 된다. 당사자 확인은 그 뒤에 한다.
   */
  @Query("select s.roomRecordId from Settlement s where s.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);
}
