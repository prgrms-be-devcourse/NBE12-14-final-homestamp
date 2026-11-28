package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.rule.CloseAllScope;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomState;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 거주 종료 행동({@code MOVE_OUT}·{@code REQUEST_END}·{@code CONFIRM_END}·{@code REJECT_END}, 06 ROOM-9)의
 * 호실 상태를 읽고 쓴다({@code StateStore}, 09 2장). 규칙은 검사하지 않는다(규칙은 {@code RoomTransitions}에만).
 *
 * <p>{@code load}는 pipeline이 호실을 잠근 뒤에 불리므로 여기서 읽은 요청·자리 상태는 최신이다(09 3-1 "계약"). 혼자 진행 판단은 room이 하지
 * 않고 {@code WaitService.canGoSoloDirectly}가 한다(03 1-1 "함수 하나").
 *
 * <p><b>거주 종료가 되는 트랜잭션</b>(이사했어요, 거주 종료 요청 확인)에서 같이 하는 일:
 *
 * <ol>
 *   <li>호실을 거주 종료로(종료 시각, 정산 자동 마감 기한)
 *   <li>그 호실의 초대 링크 무효({@code RESIDENCE_ENDED}, 03 C, ROOM-9a)
 *   <li>열려 있던 거주 종료 요청은 "거주 종료로 닫힘"(세입자가 요청에 답하지 않고 [이사했어요]를 누른 경우, #113 D 제안). 확인으로 끝나면 먼저 답함이라 닫을
 *       것이 없다
 *   <li>{@code closeAll(ROOM_ENDED)}: 하자 확인·진행 상황 묻기·상황 변화 확인은 열린 것, 종료 확인은 기다림만(#370 B-1, #398)
 * </ol>
 *
 * <p><b>아직 하지 않는 것</b>(#415): 양쪽 거주 종료 안내 알림과 받는 사람이 요청했던 닫힌 건수(실제 {@code InAppNotifier}와 측별 건수 계약,
 * NOTI-7·#116 A-5), 거주 종료 요청 문자(NOTI-1), 미확인 뒤 적용(기다림 작업자), 퇴거 점검 마침의 거주 종료(INSP-3), 닫힌 요청의 보내지 않은
 * 문자 취소(#100).
 */
@Component
public class RoomEndStateStore implements StateStore<RoomState> {

  /**
   * 혼자 진행으로 바로 거주 종료한 {@code MOVE_OUT}(집주인 측)의 혼자 진행 이유(05 2-3 payload, {@code CLOSE_ALONE}과 같은 키).
   */
  public static final String SOLO_REASON = "solo_reason";

  private static final Set<RequestStatus> OPEN =
      EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED);

  /** 거주 종료 요청에 답할 수 있는 세입자 자리(03 B "예외 둘": 공유를 그만둔 세입자도 다시 공유하지 않고 답한다). */
  private static final Set<PartyStatus> ANSWERING =
      EnumSet.of(PartyStatus.CONNECTED, PartyStatus.SHARING_STOPPED);

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final WaitService waits;
  private final WaitQuery waitQuery;
  private final InviteLinks inviteLinks;
  private final ActionRequestContext requestContext;
  private final Duration autocloseAfter;

  public RoomEndStateStore(
      RoomRecordRepository rooms,
      PartyRepository parties,
      WaitService waits,
      WaitQuery waitQuery,
      InviteLinks inviteLinks,
      ActionRequestContext requestContext,
      @Value("${app.settlement.autoclose-after:90d}") Duration autocloseAfter) {
    if (autocloseAfter.isNegative() || autocloseAfter.isZero()) {
      throw new IllegalArgumentException("app.settlement.autoclose-after는 0보다 커야 해요");
    }
    this.rooms = rooms;
    this.parties = parties;
    this.waits = waits;
    this.waitQuery = waitQuery;
    this.inviteLinks = inviteLinks;
    this.requestContext = requestContext;
    this.autocloseAfter = autocloseAfter;
  }

  @Override
  public RoomState load(ActionCommand command) {
    UUID roomRecordId = command.roomRecordId();
    RoomRecord room = room(roomRecordId);
    PartySide side = command.actor().side();
    boolean endRequestOpen =
        waitQuery
            .latest(RequestKind.END_REQUEST, RequestTarget.roomRecord(roomRecordId))
            .filter(view -> view.requestedBySide() == PartySide.LANDLORD)
            .filter(view -> OPEN.contains(view.status()))
            .isPresent();
    boolean tenantAnswers =
        parties.findByRoomRecordId(roomRecordId).stream()
            .filter(party -> party.getSide() == PartySide.TENANT)
            .map(Party::getStatus)
            .anyMatch(ANSWERING::contains);
    Boolean landlordEndsAlone = side == PartySide.LANDLORD ? soloEnd(roomRecordId).allowed() : null;
    return new RoomState(room.getStatus(), side, endRequestOpen, tenantAnswers, landlordEndsAlone);
  }

  @Override
  public SavedState save(ActionCommand command, RoomState next) {
    UUID roomRecordId = command.roomRecordId();
    RequestTarget roomTarget = RequestTarget.roomRecord(roomRecordId);
    return switch (command.type()) {
      case MOVE_OUT -> {
        SavedState saved = SavedState.unchanged();
        if (command.actor().side() == PartySide.LANDLORD) {
          // 잠근 뒤 다시 묻고 그 이유를 행동 기록에 남긴다(canGoSoloDirectly "실행할 때 다시 부른다")
          SoloCheck solo = soloEnd(roomRecordId);
          if (!solo.allowed()) {
            throw new IllegalStateException("잠근 뒤 혼자 거주 종료 판단이 바뀌었어요: " + roomRecordId);
          }
          saved = saved.withPayload(SOLO_REASON, solo.reason().name());
        }
        end(roomRecordId);
        yield saved;
      }
      case REQUEST_END -> {
        waits.open(
            new OpenRequestCommand(
                roomRecordId, RequestKind.END_REQUEST, roomTarget, PartySide.LANDLORD));
        yield SavedState.unchanged();
      }
      case CONFIRM_END -> {
        waits.answered(roomTarget, RequestKind.END_REQUEST, PartySide.TENANT);
        end(roomRecordId);
        yield SavedState.unchanged();
      }
      case REJECT_END -> {
        waits.answered(roomTarget, RequestKind.END_REQUEST, PartySide.TENANT);
        yield SavedState.unchanged();
      }
      default -> throw new IllegalArgumentException("거주 종료 행동이 아니에요: " + command.type());
    };
  }

  /** 거주 종료가 되는 트랜잭션에서 같이 하는 일(클래스 설명의 1~4). 시각은 행동 기록과 같은 {@code at()}이다. */
  private void end(UUID roomRecordId) {
    room(roomRecordId).end(requestContext.at(), autocloseAfter);
    rooms.flush();
    inviteLinks.invalidateInvites(roomRecordId, LinkInvalidReason.RESIDENCE_ENDED);
    waits.close(
        RequestTarget.roomRecord(roomRecordId), RequestKind.END_REQUEST, ClosedReason.ROOM_ENDED);
    waits.closeAll(roomRecordId, ClosedReason.ROOM_ENDED, CloseAllScope.roomEndedKinds());
  }

  /**
   * 집주인 측이 요청 없이 바로 거주 종료할 수 있나. 판단은 D의 {@code SoloRule}이다. 거주 종료 요청은 상대 공유 중단을 이유로 쓰지 않아서(03 1-1
   * ④) 지금 열리는 이유는 상대 없음과 연결 전(03 A "이사함 기록", 둘 다 성립 시각 없음)이다. 성립 시각이 있는 이유가 오면 "그 뒤 상대 행동" 확인을
   * room이 하지 않으므로 열지 않는다.
   *
   * <p>세입자 자리가 거절·탈퇴이면 {@code SoloRule}이 열지 않고(거절은 03 1-1의 이유가 아님, 탈퇴는 KEEP-4 전) 거주 종료 요청도 보낼 수 없어
   * {@code INVALID_STATE}다.
   */
  private SoloCheck soloEnd(UUID roomRecordId) {
    SoloCheck check =
        waits.canGoSoloDirectly(
            new SoloQuery(
                roomRecordId,
                RequestKind.END_REQUEST,
                RequestTarget.roomRecord(roomRecordId),
                PartySide.LANDLORD));
    return check.allowed() && check.since() == null ? check : SoloCheck.notYet(null);
  }

  private RoomRecord room(UUID roomRecordId) {
    return rooms
        .findById(roomRecordId)
        .orElseThrow(() -> new IllegalStateException("잠근 호실이 없어요: " + roomRecordId));
  }
}
