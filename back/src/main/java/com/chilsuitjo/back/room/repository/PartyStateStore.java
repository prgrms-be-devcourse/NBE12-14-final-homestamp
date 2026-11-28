package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.service.MemberQueryService;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.InviteRequestLinker;
import com.chilsuitjo.back.notification.service.SmsOptOuts;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.rule.PartyState;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상대 자리({@code state.party})를 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지
 * 않는다(규칙은 {@code PartyTransitions}에만).
 *
 * <p>{@code load}는 pipeline이 호실을 잠근 뒤에 불리므로 여기서 읽은 호실 상태·자리 상태·인증 번호는 최신이다(09 3-1 "계약"). 번호는 비교에만
 * 쓰고 {@code PartyState}·행동 payload·로그에 넣지 않는다(04 "개인정보 로그").
 */
@Component
@RequiredArgsConstructor
public class PartyStateStore {

  private final PartyRepository parties;
  private final RoomRecordRepository rooms;
  private final MemberQueryService members;
  private final InviteLinks inviteLinks;
  private final InviteRequestLinker inviteRequests;
  private final SmsOptOuts smsOptOuts;
  private final ActionRequestContext requestContext;

  /**
   * 초대({@code INVITE})의 저장 방법. 자리를 초대됨으로 바꾸고, 같은 트랜잭션에서 초대 링크를 만들고 초대 문자와 점검 확인 요청을 넣는다(06 ROOM-4c,
   * #236 {@code InviteLinks}, #399 {@code InviteRequestLinker}). 초대할 번호가 수신 거부 목록에 있는지는 {@code
   * load}에서 {@code SmsOptOuts}로 읽고 규칙({@code PartyTransitions})이 {@code PHONE_OPTED_OUT}으로 막는다(#163
   * 1-3, #330).
   *
   * @param phone 새로 넣은 번호(정규화된 번호). 비었으면 {@code null}이고 번호만 자리의 저장한 번호로 초대한다({@link #invitePhone})
   * @param record 기록 링크면 그 기록(지금은 점검만), 호실 초대 화면이면 {@code null}
   */
  public StateStore<PartyState> inviting(String phone, RequestTarget record) {
    return new Inviting(phone, record);
  }

  /**
   * 초대에 쓸 번호(03 B "상대 초대하기": 번호가 없으면 이때 입력). 새 번호가 있으면 그 번호(번호만 자리의 저장한 번호를 덮어씀, decisions
   * 2026-11-27), 없으면 번호만 자리에 저장한 번호다. 둘 다 없으면 {@code null}이다(비어 있음 자리, 또는 초대할 수 없는 자리).
   */
  private static String invitePhone(Party party, String requested) {
    if (requested != null) {
      return requested;
    }
    return party.getStatus() == PartyStatus.NUMBER_ONLY
        ? party.invitePhoneForSending().orElse(null)
        : null;
  }

  /**
   * 연결({@code CONNECT}, 06 ROOM-5a)의 저장 방법. 자리를 연결됨으로 바꾸고(초대 번호는 지움), 호실에 상대가 처음 연결한 시각을 남기고, 그 자리로
   * 보낸 초대 링크를 모두 무효로 한다({@code CONNECTED}, 03 C, #236 {@code InviteLinks}).
   */
  public StateStore<PartyState> connecting() {
    return new Connecting();
  }

  /**
   * 거절({@code REJECT_INVITE}, "제 집이 아니에요")의 저장 방법. 자리만 거절됨으로 바꾼다. 초대 링크를 무효로 할지는 D 확인 전이라 하지
   * 않는다(#163).
   */
  public StateStore<PartyState> rejecting() {
    return new Rejecting();
  }

  /**
   * 초대받은 회원의 자리 상태. 행동하는 회원의 인증 번호가 이 자리의 초대 번호와 같은지 본다(06 ROOM-5a·5b). 인증 번호가 없으면 다르다. 그 회원이 이 호실의
   * 다른 자리에 이미 있는지도 본다(불변식 13, 연결·거절 모두).
   */
  private PartyState inviteeState(ActionCommand command) {
    Party party = row(command);
    UUID memberId = command.actor().memberId();
    boolean phoneMatches = members.verifiedPhone(memberId).map(party::isInvitedPhone).orElse(false);
    boolean alreadyInRoom =
        parties.findByRoomRecordId(command.roomRecordId()).stream()
            .anyMatch(seat -> memberId.equals(seat.getMemberId()));
    return new PartyState(party.getStatus(), null, null, null, phoneMatches, alreadyInRoom);
  }

  private Party row(ActionCommand command) {
    ActionTarget target = command.target();
    if (target == null || target.kind() != TargetKind.PARTY) {
      throw new IllegalArgumentException("자리가 아닌 대상이에요: " + target);
    }
    return parties
        .findById(target.id())
        .filter(party -> party.getRoomRecordId().equals(command.roomRecordId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private RoomRecord room(ActionCommand command) {
    return rooms
        .findById(command.roomRecordId())
        .orElseThrow(() -> new IllegalStateException("잠근 호실이 없어요: " + command.roomRecordId()));
  }

  private final class Inviting implements StateStore<PartyState> {

    private final String phone;
    private final RequestTarget record;

    private Inviting(String phone, RequestTarget record) {
      this.phone = phone;
      this.record = record;
    }

    /**
     * 만든 측인지는 행동하는 회원의 측(pipeline이 잠금에서 채움)과 {@code created_by_side}로 보고, 대상 자리가 그 반대편인지도 함께 본다. 내
     * 번호인지는 행동하는 회원의 인증 번호와 비교한다(06 ROOM-4a). 인증 번호가 없으면 내 번호가 아니다. 수신 거부는 호실을 잠근 뒤 같은 트랜잭션에서 읽고,
     * 조회 실패는 잡지 않고 올린다("거부 없음"으로 바꾸지 않음, {@code SmsOptOuts}). 문자 보내기 직전 다시 확인하는 것은 D 몫이다(#163).
     *
     * <p>번호는 {@link #invitePhone}으로 정한다. 비어 있음 자리에 번호가 없으면 {@code VALIDATION_FAILED}다. 초대할 수 없는
     * 자리(초대됨·연결됨 등)는 번호가 없어도 규칙이 {@code INVALID_STATE}로 막도록 번호 검사를 비워 둔다.
     */
    @Override
    public PartyState load(ActionCommand command) {
      Party party = row(command);
      RoomRecord room = room(command);
      boolean byCreatorSide =
          command.actor().side() == room.getCreatedBySide()
              && party.getSide() != room.getCreatedBySide();
      boolean living = room.getStatus() == RoomStatus.LIVING;
      String invitePhone = invitePhone(party, phone);
      if (invitePhone == null) {
        if (party.getStatus() == PartyStatus.EMPTY) {
          throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new PartyState(party.getStatus(), byCreatorSide, living, null, null, null, null);
      }
      boolean selfInvite =
          members.verifiedPhone(command.actor().memberId()).map(invitePhone::equals).orElse(false);
      return new PartyState(
          party.getStatus(),
          byCreatorSide,
          living,
          selfInvite,
          null,
          null,
          smsOptOuts.isOptedOut(invitePhone));
    }

    /**
     * 자리 → 링크 → 문자·확인 요청 순서로 한 트랜잭션에 넣는다. 하나라도 실패하면 행동·자리도 함께 되돌아간다(06 ROOM-4c).
     *
     * <p>초대 문자는 room이 넣지 않고 {@code InviteRequestLinker.onInvite}가 넣는다(#399, #317 합의
     * 7211945235·7212382869). 받는 자리가 답할 확인 요청 상태 점검이 있으면 같은 점검 초대는 초대 문자 한 통에 그 요청을 붙이고, 다른 점검은 그
     * 점검 링크로 요청 통지를 따로 넣는다(같은 점검에 문자가 두 통 가지 않음).
     *
     * <p>기록 링크의 기록이 이 호실의 것인지는 room이 점검을 읽을 수 없어(09 3장 {@code room ← 모두}) {@code InviteLinks}가 본다.
     * 없거나 다른 호실의 기록이면 {@code NOT_FOUND}를 던지고(#280), 잡지 않고 올려 pipeline이 트랜잭션째 되돌린다.
     */
    @Override
    public SavedState save(ActionCommand command, PartyState next) {
      Party party = row(command);
      party.invite(invitePhone(party, phone));
      parties.flush();
      UUID memberId = command.actor().memberId();
      UUID linkId =
          inviteLinks.issueInvite(command.roomRecordId(), party.getId(), record, memberId);
      inviteRequests.onInvite(command.roomRecordId(), party.getId(), record, linkId, memberId);
      return SavedState.unchanged();
    }
  }

  private final class Connecting implements StateStore<PartyState> {

    @Override
    public PartyState load(ActionCommand command) {
      return inviteeState(command);
    }

    /**
     * 자리 → 호실 → 링크 순서로 한 트랜잭션에 넣는다. 시각은 행동 기록과 같은 {@code at()}이다.
     *
     * <p>먼저 연결하는 회원 행을 공유 잠금하고 활동 중인지 다시 본다({@link MemberQueryService#holdActive}, #424). 같은 회원의
     * 탈퇴가 진행 중이면 그 탈퇴가 끝날 때까지 기다렸다가 {@code LOGIN_REQUIRED}로 거절된다(탈퇴한 회원의 남은 토큰과 같음). 이 잠금을 먼저 잡았으면
     * 탈퇴가 이 연결이 끝나기를 기다렸다가 새 자리를 보고 다시 시도한다({@code WithdrawService}). 잠금 순서는 둘 다 호실 → 회원 행이다.
     */
    @Override
    public SavedState save(ActionCommand command, PartyState next) {
      if (!members.holdActive(command.actor().memberId())) {
        throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
      }
      Party party = row(command);
      RoomRecord room = room(command);
      party.connect(command.actor().memberId(), requestContext.at());
      room.markCounterpartConnected(requestContext.at());
      parties.flush();
      inviteLinks.invalidateInvites(
          command.roomRecordId(), party.getId(), LinkInvalidReason.CONNECTED);
      return SavedState.unchanged();
    }
  }

  private final class Rejecting implements StateStore<PartyState> {

    @Override
    public PartyState load(ActionCommand command) {
      return inviteeState(command);
    }

    @Override
    public SavedState save(ActionCommand command, PartyState next) {
      row(command).reject();
      return SavedState.unchanged();
    }
  }
}
