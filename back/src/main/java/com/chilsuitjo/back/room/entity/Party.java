package com.chilsuitjo.back.room.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.PhoneNumber;
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
import java.util.Optional;
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

  /**
   * 초대 회차 ID. 초대하거나 번호를 고쳐 다시 초대할 때마다 새로 정한다(#437). 받은 초대의 연결·거절이 이 값을 함께 보내 예전 초대 화면의 늦은 요청을
   * 가려낸다(X → Y → X, #370 ①). 초대 전 자리는 null이고, 연결·거절 뒤에도 마지막 회차를 남긴다.
   */
  @Getter(AccessLevel.NONE)
  @Column(name = "invite_id")
  private UUID inviteId;

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

  /** 호실을 만들 때 상대 번호만 넣어 둔 반대쪽 자리(03 B "번호만"). 초대 전이라 문자·링크는 없다. 번호는 정규화된 번호다. 초대하면 초대됨이 된다. */
  public static Party numberOnly(UUID roomRecordId, PartySide side, String phone, Instant at) {
    Party party = new Party(roomRecordId, side, null, PartyStatus.NUMBER_ONLY, null, at);
    party.invitePhone = Objects.requireNonNull(phone, "phone");
    return party;
  }

  /**
   * 이 자리의 초대 번호가 이 번호와 같은지(06 ROOM-5a·5b). 둘 다 정규화된 번호를 받는다. 번호가 지워졌거나 비교할 번호가 없으면 거짓이다. 번호 자체를 밖으로
   * 내보내지 않으려고 비교만 연다.
   */
  public boolean isInvitedPhone(String phone) {
    return invitePhone != null && invitePhone.equals(phone);
  }

  /**
   * 문자를 보낼 때만 읽는 초대 번호(notification의 {@code RecipientDirectory}, 05 {@code party.invite_phone}).
   * 지워졌으면(연결·수신 거부·탈퇴) 빈 값이다. 화면 응답·로그에 넣지 않는다(04 "개인정보 로그"). 화면에는 {@link #maskedInvitePhone}만 주고,
   * 그 밖의 곳은 {@link #isInvitedPhone}으로 비교만 한다.
   */
  public Optional<String> invitePhoneForSending() {
    return Optional.ofNullable(invitePhone).filter(phone -> !phone.isBlank());
  }

  /**
   * 화면에 보일 가린 초대 번호({@code PhoneNumber.mask}, 03 "가린 번호만"). 번호만·초대됨·거절됨처럼 번호가 남아 있을 때만 있고, 지워졌으면 빈
   * 값이다. 호실을 만든 측이 적은 번호라 그 측 화면에만 준다(부르는 쪽이 판단, {@code RoomViewService}).
   */
  public Optional<String> maskedInvitePhone() {
    return invitePhoneForSending().map(PhoneNumber::mask);
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
    this.inviteId = UUID.randomUUID();
    this.status = PartyStatus.INVITED;
  }

  /**
   * 번호를 고쳐 다시 초대한다(03 B "번호 고치기", 06 4-3 {@code counterpart-phone}): 초대됨·거절됨 → 새 번호로 초대됨. 초대 회차도 새로
   * 정해 예전 초대의 늦은 연결·거절을 받지 않는다. 규칙은 {@code PartyTransitions}가 먼저 본다. 그 밖의 상태에서 부르면 부르는 쪽 버그라 {@code
   * IllegalStateException}이다.
   */
  public void reinvite(String phone) {
    if (status != PartyStatus.INVITED && status != PartyStatus.REJECTED) {
      throw new IllegalStateException("번호를 고쳐 다시 초대할 수 없는 자리예요: " + id);
    }
    this.invitePhone = Objects.requireNonNull(phone, "phone");
    this.inviteId = UUID.randomUUID();
    this.status = PartyStatus.INVITED;
  }

  /**
   * 받은 초대 목록에 줄 지금 초대 회차. 회차 칸이 비어 있으면 자리 ID다: V14 배포 중 이전 버전 앱이 회차 없이 초대한 자리도 연결·거절할 수 있게(04 8장
   * "스키마 변경: 이전 버전 앱과도 맞게"). 그런 자리를 번호를 고쳐 다시 초대하면 새 회차가 생겨 예전 요청과 구분된다. 초대 전 자리는 빈 값이다.
   */
  public Optional<UUID> currentInviteId() {
    if (inviteId != null) {
      return Optional.of(inviteId);
    }
    return status == PartyStatus.INVITED || status == PartyStatus.REJECTED
        ? Optional.of(id)
        : Optional.empty();
  }

  /** 받은 초대에 답한 요청이 지금 초대 회차({@link #currentInviteId})의 것인지. 보낸 값이 없으면 거짓이다. */
  public boolean isCurrentInvite(UUID requestedInviteId) {
    return requestedInviteId != null
        && currentInviteId().map(requestedInviteId::equals).orElse(false);
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

  /** 이 자리의 회원이 탈퇴할 수 있는 상태인가: 연결됨·공유 중단(03 B "탈퇴"). */
  public boolean isOccupied() {
    return status == PartyStatus.CONNECTED || status == PartyStatus.SHARING_STOPPED;
  }

  /**
   * 이 자리의 회원이 탈퇴했다(03 B "탈퇴", 06 KEEP-4): 연결됨·공유 중단 → 탈퇴함. 회원 ID는 남긴다(가린 이름 "탈퇴한 임차인"을 읽고, 같은 번호로
   * 새로 가입한 계정이 이 자리에 붙지 않게, KEEP-4d). 공유를 그만둔 시각({@code sharing_stopped_at})도 지우지 않는다: 중단 동안 탈퇴자가
   * 남긴 기록을 계속 가리는 기준이다(decisions 2026-11-26). 초대 번호는 지운다(05 {@code party} "탈퇴 때 NULL"). 그 밖의 상태면
   * 부르는 쪽 버그라 {@code IllegalStateException}이다.
   */
  public void withdraw() {
    if (!isOccupied()) {
      throw new IllegalStateException("탈퇴할 수 없는 자리예요: " + id);
    }
    this.invitePhone = null;
    this.status = PartyStatus.WITHDRAWN;
  }
}
