package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RenotifyPlan;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 재통지 행동({@code RENOTIFY}, 서버)의 상태 저장. 요청 행은 바꾸지 않고 재통지 문자({@code notice} {@code RENOTIFY})를 아웃박스에
 * 넣는다. 부르는 곳은 {@code RenotifyWorker}뿐이다.
 *
 * <p><b>잠근 뒤 확인({@link #check})</b>. 호실을 잠근 뒤 부른다(작업자의 접근 검증과 {@link #load}). 하나라도 맞지 않으면 {@link
 * RenotifySkipped}이고 아무것도 쓰지 않는다:
 *
 * <ul>
 *   <li>요청이 지금도 기다림이고, 기다림이 시작됐고(첫 전달 증거가 있어야 기한이 생김), 저장된 재통지 기한이 지났고, 그 요청에 재통지 통지가 아직 없다(03 1-4
 *       "재통지 문자는 요청당 한 번", 상태 무관)
 *   <li>지금 호실 단계에서 그 종류를 열 수 있다({@code RoomParties.canOpen}: 정산 완료면 없음, 거주 종료 뒤면 정산을 마무리하는 종류만, 03
 *       K "거주 종료 뒤"). 합류 전 기록이 아니다
 *   <li>답할 자리가 연결 전이나 연결됨이고, 어느 쪽도 공유 중단 제한이 없다. 거절·탈퇴·공유 중단 때 요청을 어떻게 정리할지는 각 결정을 따르고 여기서 문자를 내지
 *       않는다
 *   <li>그 자리에 이미 닿은 요청 통지(요청 통지·요청이 걸린 초대 문자, 전달됨·열람)가 있고 그 링크가 지금도 유효하다(링크가 없는 통지면 그대로). 재통지는 그
 *       자리·그 링크로 간다(가장 최근에 만든 것). 무효가 된 링크로는 보내지 않는다: 번호를 고쳐 무효가 된 옛 링크는 옛 번호의 문자이고(#370 T-2, 새 링크
 *       통지와 기한 다시 세기는 번호 고치기 처리가 함), 다른 이유로 무효가 된 링크는 받는 사람이 열 수 없다
 * </ul>
 *
 * <p>보낼 때의 확인(수신 거부, 받는 번호, 발송 꺼짐, 보내기 직전 링크 무효)은 문자 작업자가 다른 통지와 똑같이 한다.
 */
@Component
public class RenotifyStateStore implements StateStore<RenotifyPlan> {

  /**
   * 재통지의 자리·링크를 정할 통지: 요청에 걸려 이미 닿은 요청 통지. 이 종류의 통지가 재통지 뒤에 새로 생기면 그 재통지는 옛 응답 기회의 것이다({@link
   * NoticeRepository#existsCurrentRenotify}, 작업자의 후보 조회도 같은 집합).
   */
  public static final Set<NoticeKind> EVIDENCE_KINDS =
      Collections.unmodifiableSet(EnumSet.of(NoticeKind.REQUEST, NoticeKind.INVITE));

  private static final Set<NoticeStatus> REACHED =
      EnumSet.of(NoticeStatus.DELIVERED, NoticeStatus.VIEWED);

  private static final Set<PartyPresence> CAN_ANSWER =
      EnumSet.of(PartyPresence.INVITED, PartyPresence.CONNECTED);

  private final ResponseRequestRepository requests;
  private final NoticeRepository notices;
  private final CounterpartReader counterparts;
  private final RoomGateReader rooms;
  private final InviteLinks inviteLinks;
  private final NoticeOutbox outbox;
  private final Clock clock;

  public RenotifyStateStore(
      ResponseRequestRepository requests,
      NoticeRepository notices,
      CounterpartReader counterparts,
      RoomGateReader rooms,
      InviteLinks inviteLinks,
      NoticeOutbox outbox,
      Clock clock) {
    this.requests = requests;
    this.notices = notices;
    this.counterparts = counterparts;
    this.rooms = rooms;
    this.inviteLinks = inviteLinks;
    this.outbox = outbox;
    this.clock = clock;
  }

  /**
   * 호실을 잠근 뒤 이 요청에 지금 재통지를 낼 수 있는지 보고, 낼 재통지를 돌려준다. 읽기만 한다.
   *
   * @throws RenotifySkipped 지금 낼 수 없음(위 목록)
   */
  public RenotifyPlan check(UUID roomRecordId, UUID requestId) {
    ResponseRequest request =
        requests.findById(requestId).orElseThrow(() -> new RenotifySkipped("없음"));
    if (request.getStatus() != RequestStatus.WAITING
        || request.getFirstNotifiedAt() == null
        || request.getRenotifyDueAt() == null
        || request.getRenotifyDueAt().isAfter(clock.instant())) {
      throw new RenotifySkipped("기다림 아님 또는 기한 전");
    }
    if (notices.existsCurrentRenotify(requestId, NoticeKind.RENOTIFY, EVIDENCE_KINDS)) {
      throw new RenotifySkipped("이미 재통지함");
    }
    RoomParties parties = counterparts.read(roomRecordId, request.getRequestedBySide());
    if (!parties.canOpen(request.getKind())) {
      throw new RenotifySkipped("지금 호실 단계에서 이 종류는 문자를 내지 않음");
    }
    if (rooms.read(roomRecordId, Actor.server()).joinedAway()) {
      throw new RenotifySkipped("합류 전 기록");
    }
    PartyFacts answering = parties.counterpart();
    if (answering.partyId() == null
        || !CAN_ANSWER.contains(answering.presence())
        || parties.counterpartSharingStoppedApplies()
        || parties.mySharingStoppedApplies()) {
      throw new RenotifySkipped("답할 자리가 답할 수 없음");
    }
    Optional<Notice> evidence =
        notices.findByRequest(requestId, EVIDENCE_KINDS, NoticeChannel.SMS, REACHED).stream()
            .filter(n -> answering.partyId().equals(n.getRecipientPartyId()))
            .filter(n -> n.getLinkId() == null || !inviteLinks.isInvalidatedInvite(n.getLinkId()))
            .findFirst();
    if (evidence.isEmpty()) {
      throw new RenotifySkipped("그 자리에 닿은 통지의 링크가 지금 유효하지 않음");
    }
    return new RenotifyPlan(
        roomRecordId, requestId, answering.partyId(), evidence.get().getLinkId());
  }

  @Override
  public RenotifyPlan load(ActionCommand command) {
    return check(command.roomRecordId(), Objects.requireNonNull(command.target(), "target").id());
  }

  @Override
  public SavedState save(ActionCommand command, RenotifyPlan next) {
    Objects.requireNonNull(next, "next");
    outbox.enqueueSms(
        new SmsNoticeCommand(
            next.roomRecordId(),
            next.recipientPartyId(),
            NoticeKind.RENOTIFY,
            next.requestId(),
            next.linkId()));
    return SavedState.unchanged();
  }

  /** 잠근 뒤 확인에서 재통지 대상이 아니다. 아무것도 쓰지 않았다(스택 없이 가볍게 만든다). */
  public static final class RenotifySkipped extends RuntimeException {

    private final String reason;

    RenotifySkipped(String reason) {
      super(reason, null, false, false);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }
}
