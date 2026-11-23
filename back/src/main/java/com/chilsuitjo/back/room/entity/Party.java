package com.chilsuitjo.back.room.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 당사자 자리({@code state.party}, 05 2-2, C 테이블). 한 호실에 측마다 하나다({@code UNIQUE(room_record_id, side)}).
 *
 * <p>초대 번호({@code invite_phone})는 초대(ROOM-4)를 만들 때 옮긴다.
 */
@Entity
@Table(schema = "state", name = "party")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Party {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private PartySide side;

  /** 이 자리의 회원. 아직 아무도 연결하지 않았으면 null. */
  @Column(name = "member_id")
  private UUID memberId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PartyStatus status;

  @Column(name = "connected_at")
  private Instant connectedAt;

  /** 공유를 그만둔 시각. 다시 공유하면 null(05 {@code party}). */
  @Column(name = "sharing_stopped_at")
  private Instant sharingStoppedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  private Party(
      UUID roomRecordId,
      PartySide side,
      UUID memberId,
      PartyStatus status,
      Instant connectedAt,
      Instant createdAt) {
    this.roomRecordId = Objects.requireNonNull(roomRecordId);
    this.side = Objects.requireNonNull(side);
    this.memberId = memberId;
    this.status = Objects.requireNonNull(status);
    this.connectedAt = connectedAt;
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  /** 호실을 만든 회원의 자리. 만든 때부터 이 호실에 연결돼 있다. */
  public static Party creator(UUID roomRecordId, PartySide side, UUID memberId, Instant at) {
    return new Party(
        roomRecordId, side, Objects.requireNonNull(memberId), PartyStatus.CONNECTED, at, at);
  }

  /** 반대쪽 빈 자리(03 B "비어 있음"). 초대하면 채워진다. */
  public static Party empty(UUID roomRecordId, PartySide side, Instant at) {
    return new Party(roomRecordId, side, null, PartyStatus.EMPTY, null, at);
  }
}
