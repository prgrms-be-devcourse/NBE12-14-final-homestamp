package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.RepairContent;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 수리 건 내용의 판({@code record.repair_content}, B). 새 판을 더하기만 한다. repair 안에서만 쓴다. */
public interface RepairContentRepository extends JpaRepository<RepairContent, UUID> {

  /** 그 수리 건의 판 하나. 판은 고칠 수 없어서(B) 잠금 없이 읽어도 값이 바뀌지 않는다. */
  Optional<RepairContent> findByRepairCaseIdAndNo(UUID repairCaseId, int no);

  /** 여러 수리 건의 모든 판(목록에서 건마다 보이는 판을 고를 때, 06 4-6). */
  List<RepairContent> findByRepairCaseIdIn(Collection<UUID> repairCaseIds);
}
