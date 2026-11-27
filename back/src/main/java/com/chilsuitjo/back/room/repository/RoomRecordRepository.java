package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.RoomRecord;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRecordRepository extends JpaRepository<RoomRecord, UUID> {

  /** 호실 기록 행을 {@code FOR UPDATE}로 잠그고 읽는다(09 3장 "잠금"). 트랜잭션 안에서만 부른다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoomRecord r where r.id = :id")
  Optional<RoomRecord> findByIdForUpdate(@Param("id") UUID id);

  /**
   * 이 회원이 당사자인 호실 기록, 새것부터(ID가 UUID v7이라 만든 순서). 내 호실 목록의 첫 쪽, 또는 {@code Limit.unlimited()}로 전부(홈
   * 범위 {@code RoomQueryService.roomsOf}).
   */
  @Query(
      "select r from RoomRecord r where exists (select 1 from Party p"
          + " where p.roomRecordId = r.id and p.memberId = :member) order by r.id desc")
  List<RoomRecord> findMine(@Param("member") UUID memberId, Limit limit);

  /** {@link #findMine}의 다음 쪽. {@code cursor}(앞 쪽의 마지막 호실 ID)보다 먼저 만든 것. */
  @Query(
      "select r from RoomRecord r where r.id < :cursor and exists (select 1 from Party p"
          + " where p.roomRecordId = r.id and p.memberId = :member) order by r.id desc")
  List<RoomRecord> findMineBefore(
      @Param("member") UUID memberId, @Param("cursor") UUID cursor, Limit limit);
}
