package com.chilsuitjo.back.notification.rule;

/**
 * 문자 본문(임시). 최소판은 종류마다 고정된 한 줄만 보낸다. 실제 문구·링크 주소(`/r/…`)·가린 이름은 NOTI-1에서 채운다(03 K 문자 문구).
 *
 * <p>본문에 번호·상세 주소를 넣지 않는다.
 */
public final class NoticeText {

  private NoticeText() {}

  public static String of(NoticeKind kind) {
    return switch (kind) {
      case INVITE -> "[홈스탬프] 집 기록을 함께 보자는 초대가 왔어요.";
      case REQUEST -> "[홈스탬프] 확인 요청이 왔어요.";
      case RENOTIFY -> "[홈스탬프] 아직 확인하지 않은 요청이 있어요.";
      case SETTLEMENT_INVITE -> "[홈스탬프] 퇴거 정산 확인 요청이 있어요.";
      case INFO -> "[홈스탬프] 새 알림이 있어요.";
    };
  }
}
