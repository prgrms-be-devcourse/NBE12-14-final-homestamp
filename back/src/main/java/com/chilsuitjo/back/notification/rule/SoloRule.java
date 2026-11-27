package com.chilsuitjo.back.notification.rule;

import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 요청 없이 바로 혼자 진행할 수 있나(03 1-1 "혼자 진행 가능", 1-4 "이미 혼자 진행 가능한 건의 종료", #113 Q5). 03 1-1은 "판단은 기다림 타이머
 * 모듈의 함수 하나가 하고, 수리·점검·정산·거주 종료가 모두 이 함수를 부른다"라서 도메인이 아니라 여기에 둔다. 순수 함수다. {@code WaitService} 구현이
 * 호실을 잠근 뒤 {@code CounterpartReader}로 읽은 값을 넘긴다.
 *
 * <p>지금은 <b>상대 자리 사실로 확인할 수 있는 이유만</b> 연다. 근거나 시각을 확인할 수 없는 이유는 열지 않는다.
 *
 * <ul>
 *   <li>상대 없음({@code EMPTY}·{@code NUMBER_ONLY}): 연다, 성립 시각 없음. 번호만 있는 자리는 첫 초대 전이라 문자·링크가 나간 적이
 *       없고(03 B "상대 초대하기"), 초대됨에서 번호만으로 돌아가는 전이가 없다. 상대가 이 호실에서 행동한 적이 없다
 *   <li>상대 공유 중단: 연다, 성립 시각 = {@code sharing_stopped_at}. 다시 공유하면 풀리고 또 그만둘 수 있어 시각을 둔다. 상대가 탈퇴했으면
 *       보지 않는다({@link RoomParties#counterpartSharingStoppedApplies()}). 정산 끝내기·거주 종료 요청과 수리
 *       종료({@code CLOSE_CONFIRM})에는 쓰지 않는다(03 1-1 ④. 수리 종료는 물을 상대가 처음부터 없을 때만, #113 ③')
 *   <li>상대 탈퇴: <b>아직 열지 않는다.</b> 03은 탈퇴 뒤 토큰 폐기·링크 무효로 상대가 행동할 수 없다고 정하지만, 탈퇴(KEEP-4)와 그 접근 차단이 아직
 *       없어 확인할 수 없다. KEEP-4가 접근 차단을 넣을 때 성립 시각 없이 연다
 *   <li>수신 거부: 열지 않는다. {@code optedOut}의 출처가 정해지지 않았다(#100, {@code JpaCounterpartReader} "아직 채우지 않는
 *       칸"). 회원의 문자 끔({@code smsEnabled})은 수신 거부가 아니다(03 K)
 *   <li>연결 전({@code INVITED})·미확인·문자 안 감: 열지 않는다. 이 건의 요청 이력과 기한·전달 결과가 있어야 한다(실제 기다림 타이머, #124).
 *       연결 전 상대를 바로 혼자 진행으로 보지 않는다(링크로 볼 수 있는 기록은 72시간 뒤, 03 1-1 ⑥)
 *   <li>초대 거절({@code REJECTED}): 03 1-1의 이유에 없어 열지 않는다
 * </ul>
 *
 * <p>위 세 자리 상태는 서로 겹치지 않아 이유가 둘 이상 성립하지 않는다. 이유가 겹칠 때 고르는 법(03 "확인 없음 화면 표시" ⑤의 "가장 최근")은 기록 이유를 열
 * 때 정한다.
 *
 * <p>상대 쪽 사정만 본다. 그 건의 상태·행동하는 측의 권한·{@code since} 뒤 상대 행동·거주 종료·정산 완료는 부르는 도메인 규칙이 본다.
 */
public final class SoloRule {

  /** 혼자 진행이 열리는 요청 종류(decisions "늘 지키는 원칙" 4: 하자·종료·점검·정산 끝내기·거주 종료). */
  private static final Set<RequestKind> SOLO_KINDS =
      EnumSet.of(
          RequestKind.DEFECT_CONFIRM,
          RequestKind.CLOSE_CONFIRM,
          RequestKind.INSPECTION_CONFIRM,
          RequestKind.SETTLE_FINISH,
          RequestKind.END_REQUEST);

  /**
   * 상대 공유 중단을 혼자 진행 이유로 쓰지 않는 종류(03 1-1 ④, B). 수리 종료는 공유 중단을 "잠시 안 봄"으로 보고 물을 상대가 있는 공유 기록으로
   * 둔다(#113 ③').
   */
  private static final Set<RequestKind> NOT_BY_SHARING_STOPPED =
      EnumSet.of(RequestKind.SETTLE_FINISH, RequestKind.END_REQUEST, RequestKind.CLOSE_CONFIRM);

  /** 그 이유가 이어지는 동안 상대가 이 호실에서 행동할 수 없어 성립 시각 없이 돌려줄 수 있는 이유. */
  private static final Set<SoloReason> WITHOUT_SINCE =
      EnumSet.of(SoloReason.NO_COUNTERPART, SoloReason.COUNTERPART_WITHDRAWN);

  private SoloRule() {}

  /** 기한이 지나 미확인이 되면 혼자 진행이 열리는 요청 종류인가(하자·종료·점검·정산 끝내기·거주 종료). */
  public static boolean opensSolo(RequestKind kind) {
    return SOLO_KINDS.contains(kind);
  }

  /** 이 종류를 대신해 요청 없이 바로 혼자 진행할 수 있나. {@code parties}는 행동하는 측이 {@code mine}이다. */
  public static SoloCheck direct(RoomParties parties, RequestKind kind) {
    Objects.requireNonNull(parties, "parties");
    Objects.requireNonNull(kind, "kind");
    if (!SOLO_KINDS.contains(kind)) {
      return SoloCheck.notYet(null);
    }
    PartyFacts counterpart = parties.counterpart();
    SoloCheck check =
        switch (counterpart.presence()) {
          case EMPTY, NUMBER_ONLY -> SoloCheck.allowedBecause(SoloReason.NO_COUNTERPART);
          case CONNECTED ->
              parties.counterpartSharingStoppedApplies() && !NOT_BY_SHARING_STOPPED.contains(kind)
                  ? SoloCheck.allowedSince(
                      SoloReason.SHARING_STOPPED, counterpart.sharingStoppedAt())
                  : SoloCheck.notYet(null);
          // 탈퇴는 접근 차단(KEEP-4) 전이라, 연결 전·거절은 요청 이력이 필요하거나 이유가 아니라 열지 않는다(클래스 설명)
          case WITHDRAWN, INVITED, REJECTED -> SoloCheck.notYet(null);
        };
    return requireDirectContract(check);
  }

  /**
   * 바로 혼자 진행 결과가 계약을 지키나. 허용인데 성립 시각이 없으면 시각 없이 돌려줄 수 있는 이유(상대 없음·상대 탈퇴)여야 한다. 아니면 부르는 쪽이 "그 뒤로 상대
   * 행동"을 확인하지 못한 채 열게 되므로 {@code IllegalStateException}이다(구현 버그).
   */
  public static SoloCheck requireDirectContract(SoloCheck check) {
    if (check.allowed() && check.since() == null && !WITHOUT_SINCE.contains(check.reason())) {
      throw new IllegalStateException(check.reason() + "는 성립 시각 없이 바로 혼자 진행할 수 없어요");
    }
    return check;
  }
}
