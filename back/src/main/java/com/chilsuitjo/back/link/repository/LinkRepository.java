package com.chilsuitjo.back.link.repository;

import com.chilsuitjo.back.link.entity.Link;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LinkRepository extends JpaRepository<Link, UUID> {

  Optional<Link> findByTokenHash(String tokenHash);

  /**
   * 무효 링크로 열람하려 한 횟수를 하나 올린다(05 {@code link.failed_view_count}: 링크를 식별할 수 있는 실패만 센다). 이 횟수만으로 링크를
   * 무효로 하지 않는다.
   */
  @Transactional
  @Modifying
  @Query("update Link l set l.failedViewCount = l.failedViewCount + 1 where l.id = :id")
  int countFailedView(@Param("id") UUID id);
}
