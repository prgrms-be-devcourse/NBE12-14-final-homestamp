package com.chilsuitjo.back.notification.rule;

import com.chilsuitjo.back.notification.dto.NoticeStep;
import java.util.List;

/**
 * 보낸 측이 보는 통지 상태 한 줄(03 K "보낸 사람에게", 06 NOTI-4a). 화면 문구는 화면이 정하고 여기는 어느 줄인지만 정한다. "발송 성공"이라는 줄은
 * 없다(받았다는 오해).
 *
 * <p>"내일 아침에 보낼게요"(밤·한도로 미룸)는 아직 없다. 지금은 미루는 코드가 없어 발송 요청됨은 모두 {@link #SOON}이다. 미루기(NOTI-3·9)를 만들 때
 * 미룬 사실을 저장하고 그 값으로 이 줄을 더한다(예정 시각만 보고 짐작하지 않음).
 */
public enum NoticeLine {
  /** 곧 보낼게요(발송 요청됨). */
  SOON,
  /** 보냈어요 · 받았는지 확인 중. 남은 시간 없이 "받으면 그때부터 72시간을 세요". */
  SENT,
  /** 받았어요(이때부터 남은 시간). */
  DELIVERED,
  /** 열어 봤어요(링크 열람, 회원이면 알림 확인). */
  VIEWED,
  /** 문자가 가지 않았어요(최종 실패). */
  FAILED,
  /** 서비스 안 알림으로 알렸어요(문자를 끈 회원·수신 거부 등, 아직 열지 않음). */
  IN_APP;

  /**
   * 한 요청의 통지 이력(만든 순서)에서 지금 보일 줄. 보내기 전 취소({@code CANCELLED})는 일부러 멈춘 것이라 줄을 정하지 않고(03 K "보내기 전
   * 취소"), 남은 통지 중 마지막으로 만든 것이 줄이다: 재통지·다시 보내기는 앞 통지 뒤에 만들어지므로 그것이 "지금"이다. 그런 통지가 없으면 null.
   */
  public static NoticeLine of(List<NoticeStep> history) {
    NoticeStep last = lastShown(history);
    if (last == null) {
      return null;
    }
    return switch (last.status()) {
      case VIEWED -> VIEWED;
      case REQUESTED -> last.channel() == NoticeChannel.IN_APP ? IN_APP : SOON;
      case SENT -> last.channel() == NoticeChannel.IN_APP ? IN_APP : SENT;
      case DELIVERED -> last.channel() == NoticeChannel.IN_APP ? IN_APP : DELIVERED;
      case FAILED -> FAILED;
      case CANCELLED -> throw new IllegalStateException("취소한 통지는 줄을 정하지 않아요");
    };
  }

  /** {@link #of}가 고른 통지가 재통지면 true. */
  public static boolean renotified(List<NoticeStep> history) {
    NoticeStep last = lastShown(history);
    return last != null && last.kind() == NoticeKind.RENOTIFY;
  }

  private static NoticeStep lastShown(List<NoticeStep> history) {
    NoticeStep last = null;
    for (NoticeStep step : history) {
      if (step.status() != NoticeStatus.CANCELLED) {
        last = step;
      }
    }
    return last;
  }
}
