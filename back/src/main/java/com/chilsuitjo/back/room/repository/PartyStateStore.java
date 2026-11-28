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
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.rule.PartyState;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상대 자리({@code state.party})를 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지
 * 않는다(규칙은 {@code PartyTransitions}에만).
 *
 * <p>{@code load}는 pipeline이 호실을 잠근 뒤에 불리므로 여기서 읽은 자리 상태·인증 번호는 최신이다(09 3-1 "계약"). 번호는 비교에만 쓰고
 * {@code PartyState}·행동 payload·로그에 넣지 않는다(04 "개인정보 로그").
 */
@Component
@RequiredArgsConstructor
public class PartyStateStore {

  private final PartyRepository parties;
  private final RoomRecordRepository rooms;
  private final MemberQueryService members;
  private final InviteLinks inviteLinks;
  private final ActionRequestContext requestContext;

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
      RoomRecord room =
          rooms
              .findById(command.roomRecordId())
              .orElseThrow(
                  () -> new IllegalStateException("잠근 호실이 없어요: " + command.roomRecordId()));
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
