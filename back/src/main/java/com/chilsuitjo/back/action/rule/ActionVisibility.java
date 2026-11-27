package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 보는 사람에게 보일 행동 고르기(03 3-1 보이는 범위 표, 불변식 18). 행동 목록을 그대로 보이는 조회가 같이 쓴다: 내려받기 {@code 기록.json}(06
 * DL-1)과 경과(REP-11). 상태를 다시 계산하는 상세(수리 건 등)는 각 도메인이 같은 기준으로 둔다. 순수 함수라 Repository·Clock을 부르지 않는다.
 *
 * <ul>
 *   <li>내가 남긴 행동은 늘 넣는다
 *   <li>공유 중단 기준 시각({@link #cutoff}) 뒤에 상대·서버가 남긴 행동은 뺀다. 상대가 탈퇴했으면 중단 동안 그 측이 남긴 행동은 계속 뺀다(03
 *       6-4). 거주 종료 행동과 정산 완료는 예외로 넣는다(3-1 "예외로 거주 종료 행동(이사했어요, 거주 종료 요청과 답)과 정산 완료는 양쪽에 보인다")
 *   <li>상대의 나만 보는 행동({@code CONFIRM_LIVING})과 통지 상태(3-1 "보낸 측만")는 뺀다
 *   <li>확인 요청 전인 점검(작성 중)에서 그 점검 기준 측이 남긴 점검 행동은 기준 측이 아닌 사람에게 뺀다(3-1 "작성 중 점검 → 상대 측은 '작성 중'만", 06
 *       INSP-1a). 공유 중단 기준 시각 뒤의 확인 요청은 누가 보냈든 확인 요청으로 세지 않는다(06 INSP-1f, #352). 기준 측이 아닌 측이 작성 중에
 *       더한 항목은 기준 측도 보고 답하므로 넣는다(#286, 화면의 {@code InspectionSight}와 같음)
 * </ul>
 */
public final class ActionVisibility {

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

  /**
   * 확인 요청 전(작성 중)에 쌓이는 점검 내용 행동. 그 점검의 확인 요청이 넣을 행동에 없으면 기준 측이 남긴 것은 상대에게 넣지 않는다. 점검 시작({@code
   * START_INSPECTION})은 넣는다: 3-1 "'작성 중'만"은 점검이 있다는 것은 보인다는 뜻이고, 시작 행동에는 종류·입주일만 있고 항목 내용·사진이 없다.
   */
  static final Set<ActionType> DRAFT_WORK =
      EnumSet.of(ActionType.ADD_ITEM, ActionType.ADD_PART_PHOTO);

  /** 점검 항목 행동이 어느 점검의 것인지 적는 payload 키({@code InspectionItemStateStore.INSPECTION_ID}와 같은 값). */
  static final String INSPECTION_ID = "inspectionId";

  /** 보이는 순서: 시각 순, 같은 시각이면 {@code room_seq} 순(번호는 내보내지 않는다, 03 3-1). */
  public static final Comparator<ActionEntry> ORDER =
      Comparator.comparing(ActionEntry::createdAt).thenComparingLong(ActionEntry::roomSeq);

  private ActionVisibility() {}

  /**
   * 보는 사람 기준 공유 중단 기준 시각 둘.
   *
   * @param shared 상대·서버 행동을 빼는 시각. 제한이 살아 있는 공유 중단 시각 중 이른 것이고, 없으면 null이다. 상대가 탈퇴해 풀린 공유 중단은 보지
   *     않는다(06 CONS-3p·3q)
   * @param departed 상대가 탈퇴했을 때 그 측 행동을 계속 빼는 시각. 남아 있는 공유 중단 시각(누가 그만뒀든) 중 이른 것이고, 상대가 탈퇴하지 않았거나
   *     없으면 null이다. 중단 동안 탈퇴자가 남긴 기록은 상대가 본 적이 없어 탈퇴하면 숨기고 지운다(03 6-4). 제한이 풀린 것은 남은 사람이 할 수 있는
   *     일이고, 그 기록을 보여 준다는 뜻이 아니다. 탈퇴 처리가 {@code sharing_stopped_at}을 지우지 않는다는 전제다
   */
  public record Cutoff(Instant shared, Instant departed) {

    /** 공유 중단이 없는 호실. */
    public static final Cutoff NONE = new Cutoff(null, null);
  }

  /**
   * 보는 사람 기준 공유 중단 기준 시각({@link Cutoff}). 값은 {@code CounterpartReader.read(호실, 내 측)}가 주는 {@code
   * RoomParties}의 두 자리에서 읽어 넘긴다({@code action}은 {@code notification}을 모른다, 09 3장). 상대가 탈퇴하면 공유 중단
   * 제한이 둘 다 풀린다는 판단은 {@code RoomParties.mySharingStoppedApplies}·{@code
   * counterpartSharingStoppedApplies}와 같다(06 CONS-3p·3q, 상대 탈퇴가 앞선다).
   *
   * @param mineStoppedAt 내가 공유를 그만둔 시각. 없으면 null
   * @param counterpartStoppedAt 상대가 공유를 그만둔 시각. 없으면 null
   * @param counterpartWithdrawn 상대가 탈퇴했나
   */
  public static Cutoff cutoff(
      Instant mineStoppedAt, Instant counterpartStoppedAt, boolean counterpartWithdrawn) {
    Instant stopped = earliest(mineStoppedAt, counterpartStoppedAt);
    return counterpartWithdrawn ? new Cutoff(null, stopped) : new Cutoff(stopped, null);
  }

  private static Instant earliest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isBefore(b) ? a : b;
  }

  /** 이 행동이 보는 사람에게 보이나. */
  public static boolean visible(ActionEntry entry, PartySide viewer, Cutoff cutoff) {
    if (entry.actorSide() != null && entry.actorSide() == viewer) {
      return true;
    }
    if (ONLY_ACTOR.contains(entry.type())) {
      return false;
    }
    if (SHOWN_TO_BOTH.contains(entry.type())) {
      return true;
    }
    if (after(entry, cutoff.shared())) {
      return false;
    }
    // 여기까지 온 측 있는 행동은 상대 것이다(내 행동은 위에서 넣었다)
    return entry.actorSide() == null || !after(entry, cutoff.departed());
  }

  private static boolean after(ActionEntry entry, Instant at) {
    return at != null && entry.createdAt().isAfter(at);
  }

  /**
   * 보이는 행동만 {@link #ORDER} 순으로. 기준 시각 뒤의 확인 요청은 누가 보냈든 초안을 드러내지 않는다: 보는 사람은 그 점검을 기준 시각까지 작성 중으로만
   * 봤으므로 초안도 뺀다(점검 상세 {@code InspectionSight}와 같은 경계, 기준 시각과 같은 때 보낸 것은 드러냄, #352). 그 확인 요청 행동 자체는
   * 보낸 사람에게 지금처럼 보인다. 점검의 기준 측은 그 점검 시작({@code START_INSPECTION})을 남긴 측이다. 시작 행동은 공유 중단 기준으로 빠졌어도
   * 기준 측을 아는 데만 쓰므로 전체 행동에서 찾는다.
   */
  public static List<ActionEntry> select(List<ActionEntry> all, PartySide viewer, Cutoff cutoff) {
    List<ActionEntry> passed = all.stream().filter(e -> visible(e, viewer, cutoff)).toList();
    Set<UUID> requested =
        passed.stream()
            .filter(e -> e.type() == ActionType.REQUEST_INSPECTION_CONFIRM)
            // 기준 시각 뒤의 확인 요청은 누가 보냈든 초안을 드러내지 않는다(보낸 사람 자신의 행동이라 passed에 있어도, #352)
            .filter(e -> !after(e, cutoff.shared()))
            .map(ActionVisibility::inspectionOf)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, PartySide> authors = new HashMap<>();
    for (ActionEntry entry : all) {
      UUID inspection = inspectionOf(entry);
      if (entry.type() == ActionType.START_INSPECTION
          && inspection != null
          && entry.actorSide() != null) {
        authors.putIfAbsent(inspection, entry.actorSide());
      }
    }
    return passed.stream()
        .filter(e -> !hiddenDraft(e, viewer, requested, authors))
        .sorted(ORDER)
        .toList();
  }

  /**
   * 가릴 작성 중 점검 행동인가: 확인 요청 전 점검에서 기준 측이 남긴, 기준 측이 아닌 사람이 보는 행동. 어느 점검인지나 그 점검의 기준 측을 알 수 없으면 보이지 않는
   * 쪽으로 둔다.
   */
  static boolean hiddenDraft(
      ActionEntry entry, PartySide viewer, Set<UUID> requested, Map<UUID, PartySide> authors) {
    if (!DRAFT_WORK.contains(entry.type())
        || (entry.actorSide() != null && entry.actorSide() == viewer)) {
      return false;
    }
    UUID inspection = inspectionOf(entry);
    if (inspection == null) {
      return true;
    }
    if (requested.contains(inspection)) {
      return false;
    }
    PartySide author = authors.get(inspection);
    return author == null || entry.actorSide() == null || entry.actorSide() == author;
  }

  private static UUID inspectionOf(ActionEntry entry) {
    if (entry.target() != null && entry.target().kind() == TargetKind.INSPECTION) {
      return entry.target().id();
    }
    Object id = entry.payload().get(INSPECTION_ID);
    return id == null ? null : UUID.fromString(id.toString());
  }
}
