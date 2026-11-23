package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.rule.PartyStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@link CounterpartReader}의 진짜 구현(notification이 두고 room이 구현, 09 3장 "기다림·통지"). {@code
 * state.room_record}·{@code state.party}·{@code state.member}를 읽기만 한다(#140).
 *
 * <p>값을 옮기는 법:
 *
 * <ul>
 *   <li>호실 단계: {@code room_record.status}(LIVING·ENDED·SETTLED)를 그대로 {@link RoomPhase}로
 *   <li>자리: {@code party.status}를 {@link PartyPresence}로. 05의 {@code SHARING_STOPPED}는 {@link
 *       PartyPresence#CONNECTED} + {@code sharingStoppedAt}이다({@code PartyPresence} 설명)
 *   <li>그 측의 {@code party} 행이 없으면 빈 자리({@link PartyFacts#empty()}, 초대 전)
 *   <li>문자 설정: 연결된 회원(탈퇴 아님)의 {@code member.sms_enabled}. 회원이 아니면 거짓
 * </ul>
 *
 * <p><b>아직 채우지 않는 칸</b>: {@code optedOut}은 늘 거짓, {@code maskedName}은 늘 null이다. 수신 거부는 번호 HMAC(D)이,
 * 가린 이름은 진짜 {@code MemberQueryService}(A)가 있어야 읽을 수 있다. 지금 두 칸을 읽는 곳은 없고, 처음 쓰는 문자 보내기 기능 PR에서 채운다.
 * 그 전에 두 칸으로 판단하는 코드를 더하지 않는다.
 *
 * <p>room 엔티티가 아직 없어서 네이티브 쿼리로 읽는다. 같은 트랜잭션에서 JPA로 저장하고 flush하지 않은 행도 보이게 {@link EntityManager}를
 * 쓴다(Hibernate가 네이티브 쿼리 전에 flush한다). pipeline이 호실을 잠근 뒤 같은 트랜잭션에서 부르므로 트랜잭션을 따로 열지 않는다.
 */
@Service
public class JpaCounterpartReader implements CounterpartReader {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public RoomParties read(UUID roomRecordId, PartySide mySide) {
    RoomPhase phase = phase(roomRecordId);
    Map<PartySide, PartyFacts> sides = parties(roomRecordId);
    PartySide other = mySide == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
    return new RoomParties(
        phase,
        sides.getOrDefault(mySide, PartyFacts.empty()),
        sides.getOrDefault(other, PartyFacts.empty()));
  }

  private RoomPhase phase(UUID roomRecordId) {
    List<?> rows =
        entityManager
            .createNativeQuery("SELECT status FROM state.room_record WHERE id = :room")
            .setParameter("room", roomRecordId)
            .getResultList();
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return RoomPhase.valueOf((String) rows.getFirst());
  }

  private Map<PartySide, PartyFacts> parties(UUID roomRecordId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT p.id, p.side, p.member_id, p.status, p.sharing_stopped_at, m.sms_enabled"
                    + " FROM state.party p LEFT JOIN state.member m ON m.id = p.member_id"
                    + " WHERE p.room_record_id = :room")
            .setParameter("room", roomRecordId)
            .getResultList();
    Map<PartySide, PartyFacts> sides = new EnumMap<>(PartySide.class);
    for (Object[] row : rows) {
      sides.put(PartySide.valueOf((String) row[1]), facts(row));
    }
    return sides;
  }

  private static PartyFacts facts(Object[] row) {
    PartyPresence presence = presence(PartyStatus.valueOf((String) row[3]));
    UUID memberId = presence == PartyPresence.WITHDRAWN ? null : (UUID) row[2];
    boolean smsEnabled = memberId != null && Boolean.TRUE.equals(row[5]);
    return new PartyFacts(
        (UUID) row[0], memberId, presence, instant(row[4]), smsEnabled, false, null);
  }

  /** 05 {@code party.status} → notification의 자리 값. 공유 중단은 연결된 자리다. */
  static PartyPresence presence(PartyStatus status) {
    return switch (status) {
      case EMPTY -> PartyPresence.EMPTY;
      case NUMBER_ONLY -> PartyPresence.NUMBER_ONLY;
      case INVITED -> PartyPresence.INVITED;
      case CONNECTED, SHARING_STOPPED -> PartyPresence.CONNECTED;
      case REJECTED -> PartyPresence.REJECTED;
      case WITHDRAWN -> PartyPresence.WITHDRAWN;
    };
  }

  /** 드라이버·Hibernate 판에 따라 timestamptz가 다른 타입으로 오므로 하나로 맞춘다. */
  private static Instant instant(Object value) {
    return switch (value) {
      case null -> null;
      case Instant i -> i;
      case OffsetDateTime o -> o.toInstant();
      case Timestamp t -> t.toInstant();
      default -> throw new IllegalStateException("시각 타입을 알 수 없어요: " + value.getClass());
    };
  }
}
