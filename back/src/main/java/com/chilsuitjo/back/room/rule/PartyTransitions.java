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
 *       번호면 {@code SELF_INVITE}
 *   <li>{@code CONNECT}: 초대됨 자리에 초대 번호와 같은 번호로 인증한 회원이 → 연결됨. 초대됨이 아니면 {@code INVALID_STATE}, 번호가
 *       다르면 {@code PHONE_MISMATCH}, 이 호실의 다른 자리에 이미 있는 회원이면 {@code INVALID_STATE}(한 호실에 한 회원은 한 자리,
 *       불변식 13. 초대한 사람이 나중에 번호를 바꿔 초대 번호와 같아진 경우 등). 거주 종료는 보지 않는다: 거주 종료 전에 초대받은 상대는 정산 완료 전까지 연결할
 *       수 있다(정산 완료는 pipeline이 {@code SETTLED}로 막음). 팀 최종 합의 전 임시 정책이다(#163 3-1). 받은 초대 목록({@code
 *       InvitationQueryService})도 같은 기준이다
 *   <li>{@code REJECT_INVITE}("제 집이 아니에요"): 초대됨 자리에 초대받은 사람이 → 거절됨. 03 3장 "제 집이 아니에요"는 초대받은
 *       사람만이라(권한 표 "△ 초대받은 사람") 연결과 같이 번호가 다르면 {@code PHONE_MISMATCH}, 초대됨이 아니거나 이 호실의 다른 자리에 이미 있는
 *       회원이면 {@code INVALID_STATE}다(06 4-3 {@code reject} 오류 칸, #252·#265 리뷰. 호실을 만든 사람의 번호가 나중에 초대
 *       번호와 같아져도 상대 자리를 거절하지 못하게)
 * </ul>
 *
 * <p>수신 거부 번호({@code PHONE_OPTED_OUT})는 D의 확인 창구가 정해진 뒤 더한다(#163 1-3).
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
      case INVITE -> {
        if (Boolean.FALSE.equals(now.byCreatorSide())
            || Boolean.FALSE.equals(now.living())
            || !invitable(now.status())) {
          throw invalidState();
        }
        if (Boolean.TRUE.equals(now.selfInvite())) {
          throw new BusinessException(ErrorCode.SELF_INVITE);
        }
        yield withStatus(now, PartyStatus.INVITED);
      }
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

  /** 03 B "상대 초대하기": 비어 있음·번호만 자리에서만. */
  private static boolean invitable(PartyStatus status) {
    return status == PartyStatus.EMPTY || status == PartyStatus.NUMBER_ONLY;
  }

  private static void requireInvitee(PartyState now) {
    if (now.status() != PartyStatus.INVITED) {
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
        now.alreadyInRoom());
  }

  private static BusinessException invalidState() {
    return new BusinessException(ErrorCode.INVALID_STATE);
  }
}
