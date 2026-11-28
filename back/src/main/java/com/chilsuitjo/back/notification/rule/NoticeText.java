package com.chilsuitjo.back.notification.rule;

/**
 * 문자 본문(임시). 최소판은 종류마다 고정된 한 줄에 링크 주소만 붙인다. 가린 이름·별칭·요청 내용과 회원용 주소(`/r/…`)는 NOTI-1에서 채운다(03 K 문자
 * 본문, 08 13장).
 *
 * <p>본문에 번호·상세 주소·금액을 넣지 않는다. 링크가 있으면 08 13장 ②처럼 다음 줄에 주소와 수신 거부 방법을 붙인다. 주소는 보낼 때마다 다시 만들고 이 본문은
 * 업체에만 넘긴다(저장·로그 없음).
 *
 * <p>길이: 링크 토큰이 43자라 링크가 든 문자는 단문(90바이트)을 넘어 장문으로 나간다. 본문은 장문 한도(2,000바이트) 안이다({@code
 * NoticeTextTest}).
 */
public final class NoticeText {

  /** 링크 줄 뒤에 붙이는 수신 거부 방법(08 13장 ②). */
  static final String OPT_OUT_HINT = " (수신 거부는 링크에서)";

  private NoticeText() {}

  /** 링크가 없는 문자. */
  public static String of(NoticeKind kind) {
    return switch (kind) {
      case INVITE -> "[홈스탬프] 집 기록을 함께 보자는 초대가 왔어요.";
      case REQUEST -> "[홈스탬프] 확인 요청이 왔어요.";
      case RENOTIFY -> "[홈스탬프] 아직 확인하지 않은 요청이 있어요.";
      case SETTLEMENT_INVITE -> "[홈스탬프] 퇴거 정산 확인 요청이 있어요.";
      case INFO -> "[홈스탬프] 새 알림이 있어요.";
    };
  }

  /** 링크 주소를 붙인 문자. {@code address}는 {@code LinkAddresses}가 만든 그 통지의 주소다. */
  public static String of(NoticeKind kind, String address) {
    if (address == null || address.isBlank()) {
      throw new IllegalArgumentException("address");
    }
    return of(kind) + "\n" + address + OPT_OUT_HINT;
  }
}
