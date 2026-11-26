package com.chilsuitjo.back.notification.rule;

/**
 * 업체에 물어본 문자 한 통의 전달 결과(04 4-2, 06 NOTI-3a). 업체 결과 코드를 이 셋으로 바꾸는 것은 실제 업체 구현(NOTI-11)이 한다: 4000이면
 * {@link #DELIVERED}, 3000을 뺀 3xxx면 {@link #FAILED}, 그 밖(아직 결과 없음, 찾지 못함)은 {@link #NONE}.
 *
 * <p>업체 조회 자체가 실패한 것은 여기에 없다. 그때는 {@code SmsGateway.queryResults}가 예외를 던진다(결과 없음과 다름).
 */
public enum DeliveryOutcome {
  /** 받는 휴대폰에 전달됐다는 업체 결과. 통지 "전달됨"의 유일한 근거다(03 불변식 4). */
  DELIVERED,
  /** 전달되지 않았다는 업체의 최종 결과. */
  FAILED,
  /** 아직 결과가 없거나 업체에서 그 문자를 찾지 못했다. 다음에 다시 묻는다. */
  NONE
}
