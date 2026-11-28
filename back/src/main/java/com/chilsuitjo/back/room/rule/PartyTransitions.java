package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 상대 자리 상태 전이(03 B "당사자 연결", 06 ROOM-4·5, #163). 공유 동의({@code SHARE_CONSENT})는 상태 칸이 없는 행동이라 여기서
 * 다루지 않는다(09 3-1, {@code ActionBatch.record}). 정산 완료·합류 전 기록·공유 중단은 pipeline이 먼저 막는다({@code
 * RoomGate}).
 *
 * <ul>
 *   <li>{@code INVITE}: 호실을 만든 측이, 거주 중에, 비어 있음·번호만 자리에 → 초대됨. 그 밖이면 {@code INVALID_STATE}, 내 인증
 *       번호면 {@code SELF_INVITE}, 수신 거부 목록에 있는 번호면 {@code PHONE_OPTED_OUT}(06 4-3, #163 1-3. 내 번호인지를
 *       먼저 본다)
 *   <li>{@code CHANGE_INVITE_PHONE}(번호 고쳐 다시 초대, 06 4-3 {@code counterpart-phone}, #437): 호실을 만든
 *       측이, 거주 중에, 초대됨·거절됨 자리에 → 새 번호로 초대됨. 그 밖이면 {@code INVALID_STATE}, 내 인증 번호면 {@code
 *       SELF_INVITE}, 수신 거부 목록에 있는 번호면 {@code PHONE_OPTED_OUT}(초대와 같은 순서). 지금 초대 번호와 같은 번호면 입력 오류라
 *       {@code PartyStateStore}가 잠근 뒤 {@code VALIDATION_FAILED}로 먼저 막는다
 *   <li>{@code CONNECT}: 초대됨 자리에 초대 번호와 같은 번호로 인증한 회원이 → 연결됨. 초대됨이 아니면 {@code INVALID_STATE}, 번호가
 *       다르면 {@code PHONE_MISMATCH}, 이 호실의 다른 자리에 이미 있는 회원이면 {@code INVALID_STATE}(한 호실에 한 회원은 한 자리,
 *       불변식 13. 초대한 사람이 나중에 번호를 바꿔 초대 번호와 같아진 경우 등), 보낸 초대 회차가 지금 회차와 다르면 {@code INVALID_STATE}(X →
 *       Y → X로 다시 초대했을 때 예전 초대 화면의 늦은 요청, #437·#370 ①. 자리 상태 다음, 번호보다 먼저 본다). 거주 종료는 보지 않는다: 거주 종료
 *       전에 초대받은 상대는 정산 완료 전까지 연결할 수 있다(정산 완료는 pipeline이 {@code SETTLED}로 막음). 팀 최종 합의 전 임시
 *       정책이다(#163 3-1). 받은 초대 목록({@code InvitationQueryService})도 같은 기준이다
 *   <li>{@code REJECT_INVITE}("제 집이 아니에요"): 초대됨 자리에 초대받은 사람이 → 거절됨. 03 3장 "제 집이 아니에요"는 초대받은
 *       사람만이라(권한 표 "△ 초대받은 사람") 연결과 같이 번호가 다르면 {@code PHONE_MISMATCH}, 초대됨이 아니거나 이 호실의 다른 자리에 이미 있는
 *       회원이면 {@code INVALID_STATE}다(06 4-3 {@code reject} 오류 칸, #252·#265 리뷰. 호실을 만든 사람의 번호가 나중에 초대
 *       번호와 같아져도 상대 자리를 거절하지 못하게)
 * </ul>
 */
@Component
public class PartyTransitions implements Transitions<PartyState> {

  /** 비어 있음. 요청할 때만 아는 값은 비워 둔다({@link PartyState}: 다시 계산할 때는 검사하지 않음). */
  @Override
  public PartyState initial() {
    return new PartyState(PartyStatus.EMPTY, null, null, null, null);
  }

  @Override
  public PartyState apply(PartyState now, ActionEntry action) {
    return switch (action.type()) {
      case INVITE -> invite(now, invitable(now.status()));
      case CHANGE_INVITE_PHONE -> invite(now, reinvitable(now.status()));
      case CONNECT -> {
        requireInvitee(now);
        yield withStatus(now, PartyStatus.CONNECTED);
      }
      case REJECT_INVITE -> {
        requireInvitee(now);
        yield withStatus(now, PartyStatus.REJECTED);
      }
      default -> throw invalidState();
    };
  }

  /** 초대와 번호 고쳐 다시 초대: 만든 측·거주 중·대상 자리 → 내 번호 → 수신 거부 순서로 본다. */
  private static PartyState invite(PartyState now, boolean targetSeat) {
    if (Boolean.FALSE.equals(now.byCreatorSide())
        || Boolean.FALSE.equals(now.living())
        || !targetSeat) {
      throw invalidState();
    }
    if (Boolean.TRUE.equals(now.selfInvite())) {
      throw new BusinessException(ErrorCode.SELF_INVITE);
    }
    if (Boolean.TRUE.equals(now.optedOut())) {
      throw new BusinessException(ErrorCode.PHONE_OPTED_OUT);
    }
    return withStatus(now, PartyStatus.INVITED);
  }

  /** 03 B "번호 고치기": 초대됨·거절됨 자리에서만(#437). 번호만 자리는 초대가 번호를 덮어쓴다(decisions 2026-11-27). */
  private static boolean reinvitable(PartyStatus status) {
    return status == PartyStatus.INVITED || status == PartyStatus.REJECTED;
  }

  /** 03 B "상대 초대하기": 비어 있음·번호만 자리에서만. */
  private static boolean invitable(PartyStatus status) {
    return status == PartyStatus.EMPTY || status == PartyStatus.NUMBER_ONLY;
  }

  private static void requireInvitee(PartyState now) {
    if (now.status() != PartyStatus.INVITED || Boolean.FALSE.equals(now.currentInvite())) {
      throw invalidState();
    }
    if (Boolean.FALSE.equals(now.phoneMatches())) {
      throw new BusinessException(ErrorCode.PHONE_MISMATCH);
    }
    if (Boolean.TRUE.equals(now.alreadyInRoom())) {
      throw invalidState();
    }
  }

  private static PartyState withStatus(PartyState now, PartyStatus status) {
    return new PartyState(
        status,
        now.byCreatorSide(),
        now.living(),
        now.selfInvite(),
        now.phoneMatches(),
        now.alreadyInRoom(),
        now.optedOut(),
        now.currentInvite());
  }

  private static BusinessException invalidState() {
    return new BusinessException(ErrorCode.INVALID_STATE);
  }
}
