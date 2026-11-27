package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.MemberRoom;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.dto.RoomPartView;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RoomQueryService}의 진짜 구현. 부위({@code state.room_part}, #140)와 내 호실 범위(#393)를 읽기만 한다.
 *
 * <p>내 호실 범위는 엔티티·Repository로 읽는다. 부위({@link #findPart})는 room 엔티티가 들어오기 전(#140)에 만든 네이티브 쿼리를 그대로
 * 쓴다. {@code JdbcTemplate}이 아니라 {@link EntityManager}를 쓰는 것은 같은 트랜잭션에서 JPA로 저장하고 아직 flush하지 않은 부위도
 * 보이게 하려는 것이다(Hibernate가 네이티브 쿼리 전에 flush한다).
 */
@Service
public class JpaRoomQueryService implements RoomQueryService {

  @PersistenceContext private EntityManager entityManager;

  private final PartViews partViews;
  private final RoomRecordRepository rooms;
  private final PartyRepository parties;

  public JpaRoomQueryService(
      PartViews partViews, RoomRecordRepository rooms, PartyRepository parties) {
    this.partViews = partViews;
    this.rooms = rooms;
    this.parties = parties;
  }

  /**
   * 내 호실 목록({@link RoomViewService#list})과 같은 쿼리({@code findMine})를 상한 없이 쓰고, 자리는 한 번에 읽는다. 그래서 호실
   * 수와 상관없이 쿼리가 둘이다. {@code joinedAway}는 목록과 같은 {@link JpaRoomGateReader#memberGate}, {@code asOf}는
   * 호실 상세와 같은 {@link RoomSeats#visibleCutoffFor}로 정해 따로 계산하지 않는다.
   */
  @Override
  @Transactional(readOnly = true)
  public List<MemberRoom> roomsOf(UUID memberId) {
    List<RoomRecord> mine = rooms.findMine(memberId, Limit.unlimited());
    if (mine.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<Party>> seatsByRoom =
        parties.findByRoomRecordIdIn(mine.stream().map(RoomRecord::getId).toList()).stream()
            .collect(Collectors.groupingBy(Party::getRoomRecordId));
    return mine.stream()
        .map(room -> memberRoom(new RoomSeats(room, seatsByRoom.get(room.getId())), memberId))
        .toList();
  }

  private static MemberRoom memberRoom(RoomSeats seats, UUID memberId) {
    Party me = seats.seatOf(memberId);
    Instant asOf = seats.visibleCutoffFor(me);
    return new MemberRoom(
        seats.room().getId(),
        me.getSide(),
        seats.room().getStatus(),
        JpaRoomGateReader.memberGate(seats, me).joinedAway(),
        asOf != null,
        asOf);
  }

  /** 부위 목록과 같은 계산({@link PartViews#one})을 쓴다. 목록과 등록 판단이 어긋나지 않게 따로 계산하지 않는다(#183). */
  @Override
  public RoomPartSummary findPartAsSeenBy(UUID roomRecordId, UUID roomPartId, UUID memberId) {
    RoomPartView seen = partViews.one(roomRecordId, memberId, roomPartId);
    return new RoomPartSummary(seen.name(), seen.hidden(), seen.enabled());
  }

  @Override
  public RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT name, hidden_at IS NOT NULL, enabled FROM state.room_part"
                    + " WHERE id = :part AND room_record_id = :room")
            .setParameter("part", roomPartId)
            .setParameter("room", roomRecordId)
            .getResultList();
    if (rows.isEmpty()) {
      // 없는 부위와 다른 호실의 부위를 구별하지 않는다(ROOM-2a)
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Object[] row = rows.getFirst();
    return new RoomPartSummary((String) row[0], (Boolean) row[1], (Boolean) row[2]);
  }

  @Override
  public Map<UUID, RoomPartSummary> findParts(UUID roomRecordId, Collection<UUID> roomPartIds) {
    Set<UUID> wanted = new HashSet<>(roomPartIds);
    if (wanted.isEmpty()) {
      return Map.of();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT id, name, hidden_at IS NOT NULL, enabled FROM state.room_part"
                    + " WHERE id IN (:parts) AND room_record_id = :room")
            .setParameter("parts", wanted)
            .setParameter("room", roomRecordId)
            .getResultList();
    if (rows.size() != wanted.size()) {
      // 없는 부위와 다른 호실의 부위를 구별하지 않는다(ROOM-2a, findPart와 같음)
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Map<UUID, RoomPartSummary> parts = new HashMap<>();
    for (Object[] row : rows) {
      parts.put(
          (UUID) row[0], new RoomPartSummary((String) row[1], (Boolean) row[2], (Boolean) row[3]));
    }
    return parts;
  }
}
