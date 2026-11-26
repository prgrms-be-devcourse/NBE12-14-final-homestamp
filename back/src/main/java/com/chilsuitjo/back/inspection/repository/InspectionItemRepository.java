package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.inspection.entity.InspectionItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 점검 항목 머리({@code state.inspection_item}). */
public interface InspectionItemRepository extends JpaRepository<InspectionItem, UUID> {

  Optional<InspectionItem> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 한 점검의 항목, 더한 순서(UUID v7이라 ID 순이 만든 순서). */
  List<InspectionItem> findByInspectionIdOrderByIdAsc(UUID inspectionId);

  /**
   * 항목 ID만 받는 API(06 4-7 {@code /api/inspection-items/{id}/...})가 잠글 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에
   * 읽어도 된다. 당사자 확인은 그 뒤 pipeline 잠금이 한다.
   */
  @Query("select i.roomRecordId from InspectionItem i where i.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);
}
