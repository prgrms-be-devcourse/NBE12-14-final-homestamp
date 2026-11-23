package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.RoomRecord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRecordRepository extends JpaRepository<RoomRecord, UUID> {

  /** 호실 기록 행을 {@code FOR UPDATE}로 잠그고 읽는다(09 3장 "잠금"). 트랜잭션 안에서만 부른다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoomRecord r where r.id = :id")
  Optional<RoomRecord> findByIdForUpdate(@Param("id") UUID id);
}
