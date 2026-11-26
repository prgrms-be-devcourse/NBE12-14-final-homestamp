package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 점검 항목 판({@code record.inspection_item_version}). 판은 고칠 수 없다(B). */
public interface InspectionItemVersionRepository
    extends JpaRepository<InspectionItemVersion, UUID> {}
