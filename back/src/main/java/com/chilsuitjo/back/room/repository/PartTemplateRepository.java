package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.PartTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartTemplateRepository extends JpaRepository<PartTemplate, UUID> {

  /** 기본 부위 목록 전체, 보이는 순서대로. */
  List<PartTemplate> findAllByOrderBySortOrder();
}
