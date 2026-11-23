package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.RepairCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 수리 건 머리({@code state.repair_case}). repair 안에서만 쓴다(09 2장 {@code repository/}). */
public interface RepairCaseRepository extends JpaRepository<RepairCase, UUID> {

  /** 그 호실의 수리 건. 다른 호실의 ID면 비어 있다(남의 기록은 있는지도 알 수 없게, ROOM-2a). */
  Optional<RepairCase> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);
}
