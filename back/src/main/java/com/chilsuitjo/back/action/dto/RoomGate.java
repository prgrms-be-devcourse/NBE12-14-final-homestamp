package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ActionType.Allow;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;

/**
 * pipeline이 호실 단위 거절을 판단하는 값. {@code RoomGateReader}(room 구현)가 호실 기록 행을 잠근 <b>뒤</b> 읽어 만든다. 도메인은
 * 만들지 않는다(09 3-1 "계약").
 *
 * <p>{@code action}은 {@code room}을 import할 수 없어서 room의 상태 값({@code RoomStatus}, {@code
 * PartyStatus}) 대신 예·아니오만 담는다.
 *
 * @param side 회원·링크 행동이면 그 측, 서버·운영이면 null
 * @param settled 정산 완료(읽기 전용)
 * @param joinedAway 합류 전 기록(새 행동을 받지 않음)
 * @param mySharingStopped 회원 행동일 때만 본다(링크·서버·운영 주체면 거짓). 행동하는 회원이 공유를 그만뒀고 상대가 탈퇴하지 않음. 상대가 탈퇴하면
 *     제한이 풀려 거짓이다(03 B, 06 CONS-3p)
 * @param ended 거주 종료(거주 종료·정산 완료). 새 수리 건을 받지 않음(03 불변식 9, 06 REP-1c)
 */
public record RoomGate(
    UUID roomRecordId,
    PartySide side,
    boolean settled,
    boolean joinedAway,
    boolean mySharingStopped,
    boolean ended) {

  /**
   * 이 호실에서 회원·링크 주체가 이 행동을 할 수 없는 이유(pipeline 3단계, 09 3-1). 할 수 있으면 비어 있다. 여럿이 겹치면 정산 완료 → 거주 종료 →
   * 합류 전 기록 → 공유 중단 순서로 앞의 것을 돌려준다(정산 완료면 거주 종료도 참이라 {@code SETTLED}가 먼저).
   *
   * <p>pipeline과 상세 조회의 {@code availableActions}(06 4-0 "할 수 있는 행동")가 함께 쓴다. 그래서 화면에 보이는 버튼과 실제 요청의
   * 거절이 같은 판단에서 나온다. 서버·운영 행동은 pipeline이 이 검사를 건너뛴다.
   */
  public Optional<ErrorCode> rejection(ActionType type) {
    if (settled && !type.allows(Allow.AFTER_SETTLED)) {
      return Optional.of(ErrorCode.SETTLED);
    }
    if (ended && type.blockedAfterEnded()) {
      return Optional.of(ErrorCode.ROOM_ENDED);
    }
    if (joinedAway && !type.allows(Allow.ON_JOINED)) {
      return Optional.of(ErrorCode.JOINED_RECORD);
    }
    if (mySharingStopped && !type.allows(Allow.WHILE_SHARING_STOPPED)) {
      return Optional.of(ErrorCode.SHARING_STOPPED);
    }
    return Optional.empty();
  }

  /** 거주 중이고 막히는 것이 없는 호실. */
  public static RoomGate open(UUID roomRecordId, PartySide side) {
    return new RoomGate(roomRecordId, side, false, false, false, false);
  }
}
