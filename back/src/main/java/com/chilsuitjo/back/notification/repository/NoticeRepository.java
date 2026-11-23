package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Notice;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

  /** 작업자가 결과를 적을 때. 통지는 작업자도 바꾸는 행이라 version 대신 행 잠금으로 막는다(04 "동시에 누름"). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM Notice n WHERE n.id = :id")
  Optional<Notice> findForUpdate(@Param("id") UUID id);
}
