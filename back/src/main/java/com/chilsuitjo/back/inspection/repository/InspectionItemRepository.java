package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.inspection.entity.InspectionItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 점검 항목 머리({@code state.inspection_item}). */
public interface InspectionItemRepository extends JpaRepository<InspectionItem, UUID> {

  Optional<InspectionItem> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 한 점검의 항목, 더한 순서(UUID v7이라 ID 순이 만든 순서). */
  List<InspectionItem> findByInspectionIdOrderByIdAsc(UUID inspectionId);
}
