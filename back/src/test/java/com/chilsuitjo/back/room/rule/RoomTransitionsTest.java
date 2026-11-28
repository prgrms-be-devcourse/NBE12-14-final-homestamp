package com.chilsuitjo.back.room.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 03 A 호실 기록 상태 전이 중 이번에 만든 것: 호실 기록 만들기, 거주 종료(06 ROOM-9). */
class RoomTransitionsTest {

  private static final PartySide TENANT = PartySide.TENANT;
  private static final PartySide LANDLORD = PartySide.LANDLORD;

  private final RoomTransitions rules = new RoomTransitions();

  @Test
  void 호실_기록을_만들면_거주_중이다() {
    RoomState next = rules.apply(rules.initial(), entry(ActionType.CREATE_ROOM, TENANT, null));

    assertThat(next.status()).isEqualTo(RoomStatus.LIVING);
  }

  @Test
  void 이미_있는_호실을_다시_만들_수_없다() {
    RoomState living = new RoomState(RoomStatus.LIVING);

    assertInvalid(living, entry(ActionType.CREATE_ROOM, TENANT, null));
  }

  @Test
  void 아직_받지_않는_행동은_거절한다() {
    assertInvalid(new RoomState(RoomStatus.LIVING), entry(ActionType.EDIT_ROOM_INFO, TENANT, null));
  }

  @Test
  void ROOM_9a_세입자_측이_이사했어요를_누르면_거주_종료다() {
    RoomState next = rules.apply(living(TENANT, false, true, null), moveOut(TENANT));

    assertThat(next.status()).isEqualTo(RoomStatus.ENDED);
  }

  @Test
  void 물을_세입자가_없으면_집주인_측도_바로_거주_종료다() {
    RoomState next = rules.apply(living(LANDLORD, false, false, true), moveOut(LANDLORD));

    assertThat(next.status()).isEqualTo(RoomStatus.ENDED);
  }

  @Test
  void 물을_세입자가_있으면_집주인_측_MOVE_OUT은_거절한다() {
    assertInvalid(living(LANDLORD, false, true, false), moveOut(LANDLORD));
  }

  @Test
  void 거주_중이_아니면_이사했어요를_다시_누를_수_없다() {
    for (RoomStatus status : new RoomStatus[] {RoomStatus.ENDED, RoomStatus.SETTLED}) {
      assertInvalid(new RoomState(status, TENANT, false, true, null), moveOut(TENANT));
    }
  }

  @Test
  void ROOM_9b_집주인_측_거주_종료_요청은_거주_중_그대로다() {
    RoomState next =
        rules.apply(
            living(LANDLORD, false, true, false), entry(ActionType.REQUEST_END, LANDLORD, null));

    assertThat(next.status()).isEqualTo(RoomStatus.LIVING);
    assertThat(next.endRequestOpen()).isTrue();
  }

  @Test
  void 거주_종료_요청은_한_번만_보낸다() {
    assertInvalid(
        living(LANDLORD, true, true, false), entry(ActionType.REQUEST_END, LANDLORD, null));
  }

  @Test
  void 답할_수_없는_세입자_자리에는_거주_종료_요청을_보내지_않는다() {
    // 거절·탈퇴(연결 전은 바로 거주 종료라 요청까지 오지 않음)
    assertInvalid(
        living(LANDLORD, false, false, false), entry(ActionType.REQUEST_END, LANDLORD, null));
  }

  @Test
  void 세입자_측은_거주_종료_요청을_보내지_않는다() {
    assertInvalid(living(TENANT, false, true, null), entry(ActionType.REQUEST_END, TENANT, null));
  }

  @Test
  void 세입자가_맞아요면_거주_종료다() {
    RoomState next =
        rules.apply(living(TENANT, true, true, null), entry(ActionType.CONFIRM_END, TENANT, null));

    assertThat(next.status()).isEqualTo(RoomStatus.ENDED);
  }

  @Test
  void 세입자가_아직_살고_있어요면_거주_중이고_요청은_더_열려_있지_않다() {
    RoomState next =
        rules.apply(
            living(TENANT, true, true, null), entry(ActionType.REJECT_END, TENANT, "아직 살아요"));

    assertThat(next.status()).isEqualTo(RoomStatus.LIVING);
    assertThat(next.endRequestOpen()).isFalse();
  }

  @Test
  void 아직_살고_있어요는_사유가_필요하다() {
    assertThatThrownBy(() ->
            rules.apply(
                living(TENANT, true, true, null), entry(ActionType.REJECT_END, TENANT, " ")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REASON_REQUIRED));
  }

  @Test
  void 열린_요청이_없거나_집주인_측이면_답할_수_없다() {
    assertInvalid(living(TENANT, false, true, null), entry(ActionType.CONFIRM_END, TENANT, null));
    assertInvalid(living(TENANT, false, true, null), entry(ActionType.REJECT_END, TENANT, "사유"));
    assertInvalid(
        living(LANDLORD, true, true, false), entry(ActionType.CONFIRM_END, LANDLORD, null));
  }

  @Test
  void 다시_계산할_때는_요청할_때만_아는_칸을_보지_않는다() {
    RoomState replayed = new RoomState(RoomStatus.LIVING);

    assertThat(rules.apply(replayed, moveOut(LANDLORD)).status()).isEqualTo(RoomStatus.ENDED);
    assertThat(rules.apply(replayed, entry(ActionType.CONFIRM_END, TENANT, null)).status())
        .isEqualTo(RoomStatus.ENDED);
  }

  @Test
  void 바로_거주_종료는_세입자_측이거나_혼자_종료가_허용된_집주인_측이다() {
    assertThat(living(TENANT, false, true, null).movesOutNow()).isTrue();
    assertThat(living(LANDLORD, false, false, true).movesOutNow()).isTrue();
    assertThat(living(LANDLORD, false, true, false).movesOutNow()).isFalse();
  }

  /**
   * 호실 상세 {@code availableActions}가 쓰는 세 함수가 실제 요청의 {@code apply}와 같은 답을 내는지 모든 조합으로 대 본다(06 4-0
   * "서버의 같은 함수", #463 리뷰).
   */
  @Test
  void 버튼_판단은_모든_상태_조합에서_apply와_같다() {
    for (RoomStatus status : RoomStatus.values()) {
      for (PartySide side : PartySide.values()) {
        for (boolean open : new boolean[] {false, true}) {
          for (boolean answers : new boolean[] {false, true}) {
            for (Boolean alone : new Boolean[] {null, false, true}) {
              RoomState now = new RoomState(status, side, open, answers, alone);
              String at = now.toString();
              assertThat(RoomTransitions.canMoveOut(now, side))
                  .as("MOVE_OUT %s", at)
                  .isEqualTo(accepts(now, entry(ActionType.MOVE_OUT, side, null)));
              assertThat(RoomTransitions.canRequestEnd(now, side))
                  .as("REQUEST_END %s", at)
                  .isEqualTo(accepts(now, entry(ActionType.REQUEST_END, side, null)));
              assertThat(RoomTransitions.canAnswerEnd(now, side))
                  .as("CONFIRM_END %s", at)
                  .isEqualTo(accepts(now, entry(ActionType.CONFIRM_END, side, null)))
                  .isEqualTo(accepts(now, entry(ActionType.REJECT_END, side, "사유")));
            }
          }
        }
      }
    }
  }

  private boolean accepts(RoomState now, ActionEntry action) {
    try {
      rules.apply(now, action);
      return true;
    } catch (BusinessException e) {
      assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
      return false;
    }
  }

  private void assertInvalid(RoomState now, ActionEntry action) {
    assertThatThrownBy(() -> rules.apply(now, action))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
  }

  private static RoomState living(
      PartySide side, boolean endRequestOpen, boolean tenantAnswers, Boolean landlordEndsAlone) {
    return new RoomState(RoomStatus.LIVING, side, endRequestOpen, tenantAnswers, landlordEndsAlone);
  }

  private static ActionEntry moveOut(PartySide side) {
    return entry(ActionType.MOVE_OUT, side, null);
  }

  private static ActionEntry entry(ActionType type, PartySide side, String reason) {
    UUID memberId = UUID.randomUUID();
    return new ActionEntry(
        null,
        null,
        0,
        type,
        side,
        memberId.toString(),
        "k1",
        null,
        null,
        null,
        null,
        reason,
        Map.of(),
        Instant.parse("2026-11-23T00:00:00Z"));
  }
}
