package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.RepairContent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 수리 건 내용의 판({@code record.repair_content}, B). 새 판을 더하기만 한다. repair 안에서만 쓴다. */
public interface RepairContentRepository extends JpaRepository<RepairContent, UUID> {}
