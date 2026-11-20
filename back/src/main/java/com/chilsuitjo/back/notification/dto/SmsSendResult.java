package com.chilsuitjo.back.notification.dto;

/**
 * 업체 호출 결과(04 4-2).
 *
 * <ul>
 *   <li>{@code SENT}: 업체가 받았다. {@code providerMessageId}가 있다
 *   <li>{@code REJECTED}: 업체가 받지 않겠다고 답했다(최종 실패)
 *   <li>{@code RETRY}: 업체에 닿지 않은 게 확실하다(연결 실패 등). 나중에 다시 보낸다
 *   <li>{@code UNKNOWN}: 시간 제한 등으로 결과를 모른다. 보낸 것으로 보고 다시 보내지 않는다("자동으로 두 번 보내지 않는다")
 * </ul>
 */
public record SmsSendResult(Outcome outcome, String providerMessageId) {

  public enum Outcome {
    SENT,
    REJECTED,
    RETRY,
    UNKNOWN
  }

  public static SmsSendResult sent(String providerMessageId) {
    return new SmsSendResult(Outcome.SENT, providerMessageId);
  }

  public static SmsSendResult rejected() {
    return new SmsSendResult(Outcome.REJECTED, null);
  }

  public static SmsSendResult retry() {
    return new SmsSendResult(Outcome.RETRY, null);
  }

  public static SmsSendResult unknown() {
    return new SmsSendResult(Outcome.UNKNOWN, null);
  }
}
