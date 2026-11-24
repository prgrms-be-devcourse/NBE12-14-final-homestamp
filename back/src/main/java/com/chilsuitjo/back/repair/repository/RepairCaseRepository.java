package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.RepairCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 수리 건 머리({@code state.repair_case}). repair 안에서만 쓴다(09 2장 {@code repository/}). */
public interface RepairCaseRepository extends JpaRepository<RepairCase, UUID> {

  /** 그 호실의 수리 건. 다른 호실의 ID면 비어 있다(남의 기록은 있는지도 알 수 없게, ROOM-2a). */
  Optional<RepairCase> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /**
   * 수리 건 ID만 받는 API(06 4-6 {@code /api/repair-cases/{id}/...})가 잠글 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에
   * 읽어도 된다. 당사자 확인은 그 뒤 pipeline 잠금({@code RoomGateReader})이 하고, 없는 ID와 남의 ID는 둘 다 {@code
   * NOT_FOUND}다.
   */
  @Query("select c.roomRecordId from RepairCase c where c.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);
}
