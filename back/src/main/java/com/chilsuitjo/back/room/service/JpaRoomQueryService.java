package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.dto.RoomPartView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@link RoomQueryService}의 진짜 구현. {@code state.room_part}를 읽기만 한다(#140).
 *
 * <p>room 엔티티가 아직 없어서 네이티브 쿼리로 읽는다. room 엔티티·Repository(A, ROOM-1)가 들어오면 그쪽으로 바꾼다. {@code
 * JdbcTemplate}이 아니라 {@link EntityManager}를 쓰는 것은 같은 트랜잭션에서 JPA로 저장하고 아직 flush하지 않은 부위도 보이게 하려는
 * 것이다(Hibernate가 네이티브 쿼리 전에 flush한다).
 */
@Service
public class JpaRoomQueryService implements RoomQueryService {

  @PersistenceContext private EntityManager entityManager;

  private final PartViews partViews;

  public JpaRoomQueryService(PartViews partViews) {
    this.partViews = partViews;
  }

  /** 부위 목록과 같은 계산({@link PartViews#one})을 쓴다. 목록과 등록 판단이 어긋나지 않게 따로 계산하지 않는다(#183). */
  @Override
  public RoomPartSummary findPartAsSeenBy(UUID roomRecordId, UUID roomPartId, UUID memberId) {
    RoomPartView seen = partViews.one(roomRecordId, memberId, roomPartId);
    return new RoomPartSummary(seen.name(), seen.hidden());
  }

  @Override
  public RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT name, hidden_at IS NOT NULL FROM state.room_part"
                    + " WHERE id = :part AND room_record_id = :room")
            .setParameter("part", roomPartId)
            .setParameter("room", roomRecordId)
            .getResultList();
    if (rows.isEmpty()) {
      // 없는 부위와 다른 호실의 부위를 구별하지 않는다(ROOM-2a)
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Object[] row = rows.getFirst();
    return new RoomPartSummary((String) row[0], (Boolean) row[1]);
  }
}
