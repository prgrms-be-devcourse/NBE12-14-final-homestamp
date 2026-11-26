package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.RoomPart;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 호실 부위 저장. 다른 도메인이 부위를 읽는 창구는 {@code RoomQueryService}다. */
public interface RoomPartRepository extends JpaRepository<RoomPart, UUID> {

  /** 이 호실의 부위. 다른 호실의 부위 ID면 비어 있다(ROOM-2a). */
  Optional<RoomPart> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 이 호실의 부위 전체(숨긴 부위 포함). 화면 순서는 부르는 쪽이 구역 순서로 다시 정한다. */
  List<RoomPart> findAllByRoomRecordId(UUID roomRecordId);

  /** 이 호실 부위 순서의 가장 큰 값. 부위가 없으면 0. */
  @Query(
      "SELECT COALESCE(MAX(p.sortOrder), 0) FROM RoomPart p WHERE p.roomRecordId = :roomRecordId")
  int maxSortOrder(UUID roomRecordId);
}
