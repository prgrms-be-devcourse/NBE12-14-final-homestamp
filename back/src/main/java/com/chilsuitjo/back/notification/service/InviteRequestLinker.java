package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.AwaitingConfirm;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상대를 초대할 때 초대 문자를 넣고, 받는 자리가 답할 점검 확인 요청을 그 초대에 잇는다(#370 A-7, #317 합의 7211945235·7212382869).
 *
 * <p><b>부르는 법.</b> room의 초대 {@code save} 안에서 {@link InviteLinks#issueInvite} 바로 뒤, 같은 트랜잭션에서 부른다.
 * room은 초대 문자를 따로 넣지 않는다(이 메서드가 넣음). 호실 행을 잠근 뒤라 한 호실의 초대는 줄을 서고, 무엇이든 실패하면 초대·링크·요청·문자가 함께 되돌아간다.
 *
 * <p><b>점검마다</b> 받는 자리가 답할(확인을 요청한 측이 받는 자리의 상대인) 확인 요청 상태 점검에 대해:
 *
 * <ul>
 *   <li>열린 확인 요청이 없으면 새로 연다({@link WaitService#open}, 행동 시각). 통지가 필요하다
 *   <li>열려 있고 아직 기다림이 시작되지 않았으면(기다림이고 첫 전달 시각 없음) 통지를 보완한다
 *   <li>그 밖(이미 전달됐거나 미확인·답함)은 아무것도 하지 않는다. 지금 호실 단계에서 점검 확인을 열 수 없으면 열지 않는다
 * </ul>
 *
 * <p>통지는 같은 점검 초대({@code record}가 그 점검)면 <b>초대 문자 한 통</b>에 요청을 붙인다(같은 주소의 문자가 두 통 가지 않게). 다른 점검은 그
 * 점검의 링크({@code issueInvite(호실, 자리, 점검)}, 같은 점검이면 유효한 링크를 다시 씀)로 요청 통지를 따로 넣는다. 호실 초대 링크나 다른 기록의
 * 링크는 점검 링크로 쓰지 않는다. 다른 점검에 아직 닿지 않은 요청 통지가 있으면 다시 초대해도 그 문자를 또 넣지 않는다. 단, 그 통지의 링크가 이미 무효면(번호를 고쳐
 * 다시 초대 {@code REINVITED} 등) 받는 사람이 그 문자로 열 수 없으므로 있는 통지로 치지 않고 새 링크로 다시 넣는다(#399 A 지적, #370 T-2 "새
 * 링크로 새 통지"). 기다림은 요청이 걸린 통지가 닿을 때 시작한다({@link NoticeWaitStart}). 문자를 넣는 것만으로는 시작하지 않고, 고치기 전 번호로 간
 * 통지가 닿은 것은 시작점이 아니다.
 */
@Service
public class InviteRequestLinker {

  /** 아직 닿지 않은(보내기 전·보낸 뒤 결과 전) 통지 상태. */
  private static final Set<NoticeStatus> NOT_YET_REACHED =
      EnumSet.of(NoticeStatus.REQUESTED, NoticeStatus.SENT);

  /** 닿은 통지 상태(기다림·미확인의 증거, {@link NoticeWaitStart}). */
  private static final Set<NoticeStatus> REACHED =
      EnumSet.of(NoticeStatus.DELIVERED, NoticeStatus.VIEWED);

  /** 기다림·미확인을 정한 통지 종류. */
  private static final Set<NoticeKind> REACHED_KINDS =
      EnumSet.of(NoticeKind.REQUEST, NoticeKind.INVITE, NoticeKind.RENOTIFY);

  private final ObjectProvider<InspectionsAwaitingConfirm> inspections;
  private final CounterpartReader counterparts;
  private final WaitService waits;
  private final ResponseRequestRepository requests;
  private final InviteLinks inviteLinks;
  private final NoticeOutbox outbox;
  private final NoticeRepository notices;

  public InviteRequestLinker(
      ObjectProvider<InspectionsAwaitingConfirm> inspections,
      CounterpartReader counterparts,
      WaitService waits,
      ResponseRequestRepository requests,
      InviteLinks inviteLinks,
      NoticeOutbox outbox,
      NoticeRepository notices) {
    this.inspections = inspections;
    this.counterparts = counterparts;
    this.waits = waits;
    this.requests = requests;
    this.inviteLinks = inviteLinks;
    this.outbox = outbox;
    this.notices = notices;
  }

  /**
   * 초대 문자를 넣고 그 ID를 돌려준다.
   *
   * @param roomRecordId 호실(부르는 쪽이 잠금)
   * @param recipientPartyId 받는 자리(그 호실의 자리)
   * @param record 초대로 공유한 기록({@code issueInvite}에 넘긴 값). 호실 초대면 null
   * @param inviteLinkId {@code issueInvite}가 돌려준 링크
   * @param issuedByMemberId 초대한 회원(다른 점검 링크를 받을 때 발급자)
   * @throws IllegalStateException 점검 읽기 포트({@link InspectionsAwaitingConfirm})가 아직 없을 때. 확인 요청을 빠뜨린
   *     채 초대를 보내지 않는다
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID onInvite(
      UUID roomRecordId,
      UUID recipientPartyId,
      RequestTarget record,
      UUID inviteLinkId,
      UUID issuedByMemberId) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    Objects.requireNonNull(inviteLinkId, "inviteLinkId");
    InspectionsAwaitingConfirm awaiting = inspections.getIfAvailable();
    if (awaiting == null) {
      throw new IllegalStateException("점검 확인 읽기 포트(InspectionsAwaitingConfirm)가 없어요(#317)");
    }
    RoomParties parties = counterparts.read(roomRecordId, PartySide.TENANT);
    PartySide recipientSide = sideOf(parties, recipientPartyId);
    UUID inviteRequestId = null;
    for (AwaitingConfirm inspection : awaiting.of(roomRecordId)) {
      if (inspection.baseSide() == recipientSide
          || !parties.canOpen(RequestKind.INSPECTION_CONFIRM)) {
        continue; // 받는 자리가 요청한 점검은 받는 자리가 답할 것이 아니고, 열 수 없는 단계면 열지 않는다
      }
      RequestTarget target = RequestTarget.of(TargetKind.INSPECTION, inspection.inspectionId());
      UUID requestId = requestToNotify(roomRecordId, target, inspection.baseSide());
      if (requestId == null) {
        continue;
      }
      if (target.equals(record)) {
        // 초대 문자 한 통이 요청 통지가 된다. 초대 문자는 어차피 나가므로 요청을 걸어도 문자가 늘지 않고, 앞 통지가 아직 닿지 않았어도 지금 번호로 간
        // 통지끼리는 먼저 닿는 쪽이 기다림의 시작점이다(#302 startWaiting은 더 이른 시각으로만 바꿈). 번호를 고쳐 무효가 된 옛 링크의 통지는 닿아도
        // 시작점이 아니다(NoticeWaitStart)
        inviteRequestId = requestId;
      } else if (!hasUsableNotice(requestId)) {
        // 다시 초대해도 아직 닿지 않고 링크도 살아 있는 요청 통지가 있으면 같은 문자를 또 넣지 않는다
        UUID link =
            inviteLinks.issueInvite(roomRecordId, recipientPartyId, target, issuedByMemberId);
        outbox.enqueueSms(
            new SmsNoticeCommand(
                roomRecordId, recipientPartyId, NoticeKind.REQUEST, requestId, link));
      }
    }
    return outbox.enqueueSms(
        new SmsNoticeCommand(
            roomRecordId, recipientPartyId, NoticeKind.INVITE, inviteRequestId, inviteLinkId));
  }

  /**
   * 이 요청에 아직 닿지 않았고 받는 사람이 쓸 수 있는 통지가 있나. 링크가 무효인 통지는 세지 않는다: 번호를 고치면 옛 링크가 무효가 되고 보낸(SENT) 통지는 보낸
   * 사실로 남는데, 그 문자는 옛 번호로 갔고 링크도 열리지 않는다(#370 T-2). 다른 이유로 무효인 링크도 열리지 않으니 세지 않는다(연결 {@code
   * CONNECTED}로 무효가 된 자리는 다시 초대하지 않아 이 메서드가 불리지 않는다). 링크가 없는 통지는 무효가 될 링크가 없어 센다. 호실을 잠근 뒤라 번호 고치기의
   * 무효화와 겹치지 않는다.
   */
  private boolean hasUsableNotice(UUID requestId) {
    return notices.findLinkIdsByRequestAndStatusIn(requestId, NOT_YET_REACHED).stream()
        .anyMatch(link -> link == null || !inviteLinks.isInvalidatedInvite(link));
  }

  /** 통지가 필요한 요청 ID. 새로 열었거나 아직 기다림이 시작되지 않은 기다림이면 그 ID, 아니면 null. */
  private UUID requestToNotify(UUID roomRecordId, RequestTarget target, PartySide baseSide) {
    RequestHandle handle =
        waits.open(
            new OpenRequestCommand(roomRecordId, RequestKind.INSPECTION_CONFIRM, target, baseSide));
    if (handle.created()) {
      // 통지는 (요청, 호실) 복합 외래 키로 요청을 가리키고 연관 관계가 없다. 엔티티 종류별로 삽입을 모으는 경우에도 요청이 먼저 들어가게 내보낸다
      requests.flush();
      return handle.requestId();
    }
    ResponseRequest request =
        requests
            .findById(handle.requestId())
            .orElseThrow(() -> new IllegalStateException("연 요청이 없어요"));
    if (request.getStatus() != RequestStatus.WAITING) {
      return null;
    }
    if (!request.isWaitStarted()) {
      return request.getId();
    }
    // 기다림이 옛 번호로만 시작됐으면(닿은 통지의 링크가 모두 번호 고치기로 무효) 처음으로 되돌려 새 번호 통지에 다시 잇는다(#370 T-2)
    // 쓸 수 있는 미도달 통지가 남아 있으면 새 통지를 만들지 않아(onInvite) 새 기회 표지가 생기지 않으므로 되돌리지 않는다
    if (startedOnlyByOldNumber(request.getId())
        && !hasUsableNotice(request.getId())
        && request.restartForNewNumber()) {
      requests.flush();
      return request.getId();
    }
    return null;
  }

  /**
   * 이 요청에 닿은 문자 통지(요청·초대·재통지)가 있고, 그 링크가 모두 번호 고치기로 무효({@code REINVITED})인가. 하나라도 지금 번호에 닿은 통지(유효한
   * 링크나 링크 없는 통지)가 있으면 false다. 부르는 쪽(room의 번호 고치기 저장)이 이 호출 전에 그 자리 링크를 {@code REINVITED}로 닫는다.
   *
   * <p>번호 고치기 전에 다른 이유로 이미 무효였던 링크(무효 이유는 처음 것이 남음)면 false다. 번호를 고칠 수 있는 자리(초대됨·거절됨)에서 그런 링크는 초대
   * 거절의 {@code INVITE_REJECTED}뿐인데(#478), 거절이 초대한 측의 열린 요청을 닫아 여기 오는 기다림이 없고 새로 연 요청은 처음 통지로 간다.
   * 문자가 아닌 통지(IN_APP 대체, NOTI-1 뒤)만 닿았으면 비어 false다. 그때 이 조건을 다시 본다.
   */
  private boolean startedOnlyByOldNumber(UUID requestId) {
    List<Notice> reached =
        notices.findByRequest(requestId, REACHED_KINDS, NoticeChannel.SMS, REACHED);
    return !reached.isEmpty()
        && reached.stream()
            .allMatch(n -> n.getLinkId() != null && inviteLinks.isReinvited(n.getLinkId()));
  }

  private static PartySide sideOf(RoomParties parties, UUID partyId) {
    if (partyId.equals(parties.mine().partyId())) {
      return PartySide.TENANT;
    }
    if (partyId.equals(parties.counterpart().partyId())) {
      return PartySide.LANDLORD;
    }
    throw new IllegalArgumentException("그 호실의 자리가 아니에요");
  }
}
