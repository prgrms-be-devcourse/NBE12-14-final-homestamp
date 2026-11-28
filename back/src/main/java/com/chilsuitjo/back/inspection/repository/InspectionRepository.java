package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 점검({@code state.inspection}). inspection 안에서만 쓴다(09 2장 {@code repository/}). */
public interface InspectionRepository extends JpaRepository<Inspection, UUID> {

  /** 그 호실의 점검. 다른 호실의 ID면 비어 있다(ROOM-2a). */
  Optional<Inspection> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 그 호실의 이 종류 점검(호실마다 종류별로 하나, {@code uq_inspection_kind}). */
  Optional<Inspection> findByRoomRecordIdAndKind(UUID roomRecordId, InspectionKind kind);

  /** 그 호실에서 이 상태인 점검, 만든 순서대로({@code InspectionsAwaitingConfirm}). */
  List<Inspection> findByRoomRecordIdAndStatusOrderByCreatedAtAsc(
      UUID roomRecordId, InspectionStatus status);

  /**
   * 점검 ID만 받는 API(06 4-7 {@code /api/inspections/{id}/...})가 잠글 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에 읽어도
   * 된다. 당사자 확인은 그 뒤 pipeline 잠금이 한다.
   */
  @Query("select i.roomRecordId from Inspection i where i.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);
}
