package com.chilsuitjo.back.notification.rule;

import java.util.EnumSet;
import java.util.Set;

/**
 * 호실 단계에서 열 수 있는 확인 요청 종류(03 K "거주 종료 뒤", decisions 2026-11-18 다시 공유 규칙). {@code
 * WaitService.canOpen}과 재통지·미확인 작업이 같은 판단을 쓴다(#86).
 *
 * <ul>
 *   <li>거주 중: 모두
 *   <li>거주 종료 뒤: 정산을 마무리하는 확인만(점검 확인, 고친 내용 확인, 정산 끝내기, 반환 수령, 돈 안건·지불 금액)
 *   <li>정산 완료 뒤: 없음
 * </ul>
 *
 * <p>거주 종료 뒤의 수리비 나누기 안건·지불 금액 확인(열 수 있음)과 수리 종료 확인(열 수 없음)은 #86에서 확인 중인 제안이다. 정해지면 이 목록만 고친다.
 */
public final class RequestOpening {

  private static final Set<RequestKind> AFTER_RESIDENCE_END =
      EnumSet.of(
          RequestKind.INSPECTION_CONFIRM,
          RequestKind.CORRECTION_CONFIRM,
          RequestKind.SETTLE_FINISH,
          RequestKind.RECEIPT_CONFIRM,
          RequestKind.PROPOSAL,
          RequestKind.AMOUNT_CONFIRM);

  private RequestOpening() {}

  public static boolean allowed(RoomPhase phase, RequestKind kind) {
    return switch (phase) {
      case LIVING -> true;
      case ENDED -> AFTER_RESIDENCE_END.contains(kind);
      case SETTLED -> false;
    };
  }
}
