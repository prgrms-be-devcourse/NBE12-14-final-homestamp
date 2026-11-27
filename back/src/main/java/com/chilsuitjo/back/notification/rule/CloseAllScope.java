package com.chilsuitjo.back.notification.rule;

import com.chilsuitjo.back.global.common.ClosedReason;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 호실 전체 닫기({@code WaitService.closeAll})에서 정해진 범위만 받는다(#113). 정해지지 않은 이유·종류는 조용히 0건으로 넘기지 않고 {@code
 * UnsupportedOperationException}이다.
 *
 * <ul>
 *   <li>상대 탈퇴({@code COUNTERPART_WITHDRAWN}): 그 호실의 열린 요청(기다림·미확인) 모두, 양방향(decisions 2026-11-20, 03
 *       L). 일부 종류만 닫으면 탈퇴 뒤 기다림이 남으므로 종류는 전부여야 한다
 *   <li>거주 종료({@code ROOM_ENDED}): 문서끼리 범위가 달라 아직 받지 않는다. 05 {@code response_request}는
 *       기다림(WAITING)인 하자 확인·진행 상황 묻기·상황 변화 확인, 03 K·decisions 2026-11-13은 "열린" 것(미확인 포함), 03 A는
 *       기다리던 종료 확인도 닫는다고 적는다(#113)
 *   <li>공유 중단·정산 완료 등 그 밖의 이유: #113에서 정하기 전
 * </ul>
 */
public final class CloseAllScope {

  private CloseAllScope() {}

  /** 이 이유·종류로 닫을 요청 상태. 정해지지 않은 범위면 예외. */
  public static Set<RequestStatus> statuses(ClosedReason reason, Set<RequestKind> kinds) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(kinds, "kinds");
    if (kinds.isEmpty()) {
      throw new IllegalArgumentException("닫을 종류가 비었어요");
    }
    return switch (reason) {
      case COUNTERPART_WITHDRAWN -> {
        if (!kinds.containsAll(EnumSet.allOf(RequestKind.class))) {
          throw new IllegalArgumentException("탈퇴는 그 호실의 열린 요청을 모든 종류 닫아요");
        }
        yield EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED);
      }
      default ->
          throw new UnsupportedOperationException(
              "closeAll " + reason + "은 #113에서 닫는 범위를 정한 뒤 구현해요");
    };
  }
}
