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
 * <p>자리 상태는 {@code PartyTransitions}가 정한 다음 상태로만 바꾼다(초대·연결·거절, 03 B).
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

  /**
   * 초대 번호(숫자만, 05 0장 정규화). 문자를 보내야 해서 평문이고 연결·수신 거부·탈퇴 때 지운다(05 {@code party}). 로그·예외 문구에 남기지
   * 않는다(04 "개인정보 로그").
   */
  @Getter(AccessLevel.NONE)
  @Column(name = "invite_phone", length = 20)
  private String invitePhone;

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

  /**
   * 이 자리의 초대 번호가 이 번호와 같은지(06 ROOM-5a·5b). 둘 다 정규화된 번호를 받는다. 번호가 지워졌거나 비교할 번호가 없으면 거짓이다. 번호 자체를 밖으로
   * 내보내지 않으려고 비교만 연다.
   */
  public boolean isInvitedPhone(String phone) {
    return invitePhone != null && invitePhone.equals(phone);
  }

  /**
   * 상대를 초대한다(03 B "상대 초대하기"): 비어 있음·번호만 → 초대됨. 번호는 정규화된 번호다. 규칙은 {@code PartyTransitions}가 먼저 본다. 그
   * 밖의 상태에서 부르면 부르는 쪽 버그라 {@code IllegalStateException}이다.
   */
  public void invite(String phone) {
    if (status != PartyStatus.EMPTY && status != PartyStatus.NUMBER_ONLY) {
      throw new IllegalStateException("초대할 수 없는 자리예요: " + id);
    }
    this.invitePhone = Objects.requireNonNull(phone, "phone");
    this.status = PartyStatus.INVITED;
  }

  /**
   * 초대받은 회원이 이 자리에 연결한다(03 B "연결 확인"): 초대됨 → 연결됨. 초대 번호는 지운다(05 {@code party}). 초대됨이 아니면 부르는 쪽 버그라
   * {@code IllegalStateException}이다.
   */
  public void connect(UUID connectingMemberId, Instant at) {
    if (status != PartyStatus.INVITED) {
      throw new IllegalStateException("연결할 수 없는 자리예요: " + id);
    }
    this.memberId = Objects.requireNonNull(connectingMemberId, "connectingMemberId");
    this.connectedAt = Objects.requireNonNull(at, "at");
    this.invitePhone = null;
    this.status = PartyStatus.CONNECTED;
  }

  /**
   * 초대받은 사람이 "제 집이 아니에요"를 누른다(03 B): 초대됨 → 거절됨. 초대 번호는 지우지 않는다(번호를 고쳐 다시 초대할 수 있는 자리, 03 B "번호
   * 고치기"). 초대됨이 아니면 부르는 쪽 버그라 {@code IllegalStateException}이다.
   */
  public void reject() {
    if (status != PartyStatus.INVITED) {
      throw new IllegalStateException("거절할 수 없는 자리예요: " + id);
    }
    this.status = PartyStatus.REJECTED;
  }
}
