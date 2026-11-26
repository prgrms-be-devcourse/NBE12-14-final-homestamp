package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 점검 항목 판({@code record.inspection_item_version}). 판은 고칠 수 없다(B). */
public interface InspectionItemVersionRepository
    extends JpaRepository<InspectionItemVersion, UUID> {

  /** 한 항목의 그 번호 판. 응답할 때 본 판의 내용 지문에 쓴다(안내서 3-3). */
  Optional<InspectionItemVersion> findByInspectionItemIdAndNo(UUID inspectionItemId, int no);
}
