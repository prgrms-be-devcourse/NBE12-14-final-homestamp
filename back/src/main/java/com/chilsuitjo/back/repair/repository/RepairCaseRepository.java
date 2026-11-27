package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.repair.entity.RepairCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 수리 건 머리({@code state.repair_case}). repair 안에서만 쓴다(09 2장 {@code repository/}). */
public interface RepairCaseRepository extends JpaRepository<RepairCase, UUID> {

  /** 그 호실의 수리 건. 다른 호실의 ID면 비어 있다(남의 기록은 있는지도 알 수 없게, ROOM-2a). */
  Optional<RepairCase> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /**
   * 수리 건 ID만 받는 API(06 4-6 {@code /api/repair-cases/{id}/...})가 잠글 호실을 찾는다. 호실 기록 칸은 바뀌지 않아 잠금 전에
   * 읽어도 된다. 당사자 확인은 그 뒤 pipeline 잠금({@code RoomGateReader})이 하고, 없는 ID와 남의 ID는 둘 다 {@code
   * NOT_FOUND}다.
   */
  @Query("select c.roomRecordId from RepairCase c where c.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);

  /**
   * 호실의 수리 건 목록 첫 쪽(06 4-6), 새것부터(등록 시각, 같으면 ID). 보는 사람 측이 등록한 건은 모두, 상대 측이 등록한 건은 {@code
   * hiddenAfter} 이전 것만이다(그 뒤 것은 보는 사람이 볼 수 없는 기록, 03 불변식 18).
   *
   * @param hiddenAfter 상대 측 등록 기록이 보는 사람에게 숨는 시각. 숨는 기록이 없으면 아주 먼 시각을 넘긴다
   */
  @Query(
      "select c from RepairCase c where c.roomRecordId = :room"
          + " and (c.createdBySide = :viewer or c.createdAt <= :hiddenAfter)"
          + " order by c.createdAt desc, c.id desc")
  List<RepairCase> findVisible(
      @Param("room") UUID roomRecordId,
      @Param("viewer") PartySide viewer,
      @Param("hiddenAfter") Instant hiddenAfter,
      Limit limit);

  /** {@link #findVisible}의 다음 쪽. 커서 건({@code at}, {@code id})보다 뒤에 오는 것. */
  @Query(
      "select c from RepairCase c where c.roomRecordId = :room"
          + " and (c.createdBySide = :viewer or c.createdAt <= :hiddenAfter)"
          + " and (c.createdAt < :at or (c.createdAt = :at and c.id < :id))"
          + " order by c.createdAt desc, c.id desc")
  List<RepairCase> findVisibleAfter(
      @Param("room") UUID roomRecordId,
      @Param("viewer") PartySide viewer,
      @Param("hiddenAfter") Instant hiddenAfter,
      @Param("at") Instant cursorCreatedAt,
      @Param("id") UUID cursorId,
      Limit limit);
}
