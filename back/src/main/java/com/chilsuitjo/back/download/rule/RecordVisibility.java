package com.chilsuitjo.back.download.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RoomParties;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 내려받기 {@code 기록.json}에 넣을 행동 고르기(03 3-1 보이는 범위 표, 불변식 18). 순수 함수라 Repository·Clock을 부르지 않는다.
 *
 * <ul>
 *   <li>내가 남긴 행동은 늘 넣는다
 *   <li>공유 중단 기준 시각({@link #cutoff}) 뒤에 상대·서버가 남긴 행동은 뺀다. 거주 종료 행동과 정산 완료는 예외로 넣는다(3-1 "예외로 거주 종료
 *       행동(이사했어요, 거주 종료 요청과 답)과 정산 완료는 양쪽에 보인다")
 *   <li>상대의 나만 보는 행동({@code CONFIRM_LIVING})과 통지 상태(3-1 "보낸 측만")는 뺀다
 * </ul>
 */
public final class RecordVisibility {

  /** 공유 중단 뒤에도 양쪽에 보이는 행동(03 3-1, 불변식 18). */
  static final Set<ActionType> SHOWN_TO_BOTH =
      EnumSet.of(
          ActionType.MOVE_OUT,
          ActionType.REQUEST_END,
          ActionType.CONFIRM_END,
          ActionType.REJECT_END,
          ActionType.CONFIRM_FINISH,
          ActionType.FINISH_ALONE,
          ActionType.AUTOCLOSE);

  /**
   * 남긴 측에게만 보이는 행동. 거주 확인은 나만 보는 행동이고(03 B 공유 그만두기), 열람·다시 보내기·재통지·전달 안 됨은 통지 상태다(3-1 "보낸 측만"). 통지
   * 상태를 보낸 측에게 넣는 것은 보낸 측을 행동에서 알 수 없어 이번에는 하지 않는다(서버 행동은 측이 없다).
   */
  static final Set<ActionType> ONLY_ACTOR =
      EnumSet.of(
          ActionType.CONFIRM_LIVING,
          ActionType.LINK_VIEW,
          ActionType.RESEND_NOTICE,
          ActionType.RENOTIFY,
          ActionType.MARK_UNDELIVERED);

  /** {@code 기록.json} 순서: 시각 순, 같은 시각이면 {@code room_seq} 순(번호는 파일에 넣지 않는다). */
  public static final Comparator<ActionEntry> ORDER =
      Comparator.comparing(ActionEntry::createdAt).thenComparingLong(ActionEntry::roomSeq);

  private RecordVisibility() {}

  /**
   * 보는 사람 기준 공유 중단 기준 시각. 제한이 살아 있는 공유 중단 시각 중 이른 것이고, 없으면 null이다. 상대가 탈퇴해 풀린 공유 중단은 보지 않는다(06
   * CONS-3p·3q, {@link RoomParties}). 수리 건 상세의 {@code asOf}와 같은 기준이다.
   */
  public static Instant cutoff(RoomParties room) {
    Instant stopped = null;
    if (room.mySharingStoppedApplies()) {
      stopped = room.mine().sharingStoppedAt();
    }
    if (room.counterpartSharingStoppedApplies()) {
      Instant theirs = room.counterpart().sharingStoppedAt();
      stopped = stopped == null || theirs.isBefore(stopped) ? theirs : stopped;
    }
    return stopped;
  }

  /** 이 행동을 보는 사람의 {@code 기록.json}에 넣나. */
  public static boolean visible(ActionEntry entry, PartySide viewer, Instant cutoff) {
    if (entry.actorSide() != null && entry.actorSide() == viewer) {
      return true;
    }
    if (ONLY_ACTOR.contains(entry.type())) {
      return false;
    }
    return cutoff == null
        || !entry.createdAt().isAfter(cutoff)
        || SHOWN_TO_BOTH.contains(entry.type());
  }

  /** 넣을 행동만 {@link #ORDER} 순으로. */
  public static List<ActionEntry> select(List<ActionEntry> all, PartySide viewer, Instant cutoff) {
    return all.stream().filter(e -> visible(e, viewer, cutoff)).sorted(ORDER).toList();
  }
}
