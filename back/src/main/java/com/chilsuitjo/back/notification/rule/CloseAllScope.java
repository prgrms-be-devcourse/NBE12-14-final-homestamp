package com.chilsuitjo.back.notification.rule;

import com.chilsuitjo.back.global.common.ClosedReason;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 호실 전체 닫기({@code WaitService.closeAll})에서 정해진 범위만 받는다(#113). 정해지지 않은 이유·종류는 조용히 0건으로 넘기지 않고 예외다.
 *
 * <ul>
 *   <li>상대 탈퇴({@code COUNTERPART_WITHDRAWN}): 그 호실의 열린 요청(기다림·미확인) 모두, 양방향(decisions 2026-11-20, 03
 *       L). 일부 종류만 닫으면 탈퇴 뒤 기다림이 남으므로 종류는 전부여야 한다
 *   <li>거주 종료({@code ROOM_ENDED}, #370 B-1): 하자 확인·진행 상황 묻기·상황 변화 확인은 열린 것(기다림·미확인) 모두, 종료 확인은
 *       기다림만(미확인 종료 확인과 수리 상태는 그대로, 03 3장 권한 표 "열린 수리 건의 수리 행동"). 종류는 이 넷이어야 한다(그 밖의 요청은 거주 종료로 닫지
 *       않는다)
 *   <li>정산 완료({@code SETTLED}, 06 SET-5f): 그 호실의 열린 요청(기다림·미확인) 모두, 양방향. 종류는 전부여야 한다
 *   <li>공유 중단 등 그 밖의 이유: #113에서 정하기 전이라 {@code UnsupportedOperationException}
 * </ul>
 *
 * <p>닫힌 요청은 다시 열리지 않는다. 거주 종료 뒤에도 정산 완료 전까지 기록 화면에서 늦게 답할 수 있지만(03 1-4 "72시간 뒤"), 그 답은 그 기록의 행동이고
 * 닫힌 요청을 ANSWERED로 바꾸지 않는다({@code WaitService.answered}는 열린 요청만 바꿈).
 */
public final class CloseAllScope {

  private static final Set<RequestStatus> OPEN =
      EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED);

  private static final Map<RequestKind, Set<RequestStatus>> ROOM_ENDED =
      Map.of(
          RequestKind.DEFECT_CONFIRM, OPEN,
          RequestKind.PROGRESS_CHECK, OPEN,
          RequestKind.SITUATION_CHECK, OPEN,
          RequestKind.CLOSE_CONFIRM, EnumSet.of(RequestStatus.WAITING));

  private CloseAllScope() {}

  /** 이 이유로 닫을 요청 종류와 종류마다 닫을 상태. 정해지지 않은 범위면 예외. */
  public static Map<RequestKind, Set<RequestStatus>> targets(
      ClosedReason reason, Set<RequestKind> kinds) {
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
        Map<RequestKind, Set<RequestStatus>> all = new EnumMap<>(RequestKind.class);
        kinds.forEach(kind -> all.put(kind, OPEN));
        yield all;
      }
      case SETTLED -> {
        // 정산 완료(확인·혼자 끝내기·자동 마감): 남은 기다림은 모두 닫힌다(06 SET-5f, 03 1-4 "정산 끝내기"). 미확인도 늦은 답이 끝나 닫는다
        if (!kinds.containsAll(EnumSet.allOf(RequestKind.class))) {
          throw new IllegalArgumentException("정산 완료는 그 호실의 열린 요청을 모든 종류 닫아요");
        }
        Map<RequestKind, Set<RequestStatus>> all = new EnumMap<>(RequestKind.class);
        kinds.forEach(kind -> all.put(kind, OPEN));
        yield all;
      }
      case ROOM_ENDED -> {
        if (!kinds.equals(ROOM_ENDED.keySet())) {
          throw new IllegalArgumentException(
              "거주 종료는 하자 확인·진행 상황 묻기·상황 변화 확인·종료 확인을 함께 닫아요: " + kinds);
        }
        yield ROOM_ENDED;
      }
      default ->
          throw new UnsupportedOperationException(
              "closeAll " + reason + "은 #113에서 닫는 범위를 정한 뒤 구현해요");
    };
  }

  /** 거주 종료 저장이 넘길 종류(넷 모두). */
  public static Set<RequestKind> roomEndedKinds() {
    return EnumSet.copyOf(ROOM_ENDED.keySet());
  }
}
