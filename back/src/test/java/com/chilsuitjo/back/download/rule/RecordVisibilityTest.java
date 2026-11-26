package com.chilsuitjo.back.download.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 내려받기에 넣을 행동 고르기(03 3-1, 불변식 18, #246). */
class RecordVisibilityTest {

  private static final Instant T0 = Instant.parse("2026-11-26T01:00:00Z");
  private static final Instant CUTOFF = T0.plusSeconds(60);
  private static final Instant AFTER = CUTOFF.plusSeconds(1);

  @Test
  void 시각_순으로_놓고_같은_시각이면_남긴_순으로_놓는다() {
    ActionEntry late = entry(3, ActionType.SUPPLEMENT, PartySide.TENANT, T0.plusSeconds(5));
    ActionEntry sameB = entry(2, ActionType.RESPOND_DEFECT, PartySide.LANDLORD, T0);
    ActionEntry sameA = entry(1, ActionType.REGISTER, PartySide.TENANT, T0);

    assertThat(RecordVisibility.select(List.of(late, sameB, sameA), PartySide.TENANT, null))
        .containsExactly(sameA, sameB, late);
  }

  @Test
  void 공유_중단_뒤_상대_행동은_빼고_내_행동은_넣는다() {
    ActionEntry theirs = entry(1, ActionType.RESPOND_DEFECT, PartySide.LANDLORD, AFTER);
    ActionEntry mine = entry(2, ActionType.NOTE_PROGRESS, PartySide.TENANT, AFTER);
    ActionEntry before = entry(3, ActionType.RESPOND_DEFECT, PartySide.LANDLORD, CUTOFF);

    assertThat(RecordVisibility.select(List.of(theirs, mine, before), PartySide.TENANT, CUTOFF))
        .containsExactly(before, mine);
  }

  @Test
  void 공유_중단_뒤_서버_행동도_뺀다() {
    ActionEntry server = entry(1, ActionType.MARK_UNCONFIRMED, null, AFTER);

    assertThat(RecordVisibility.visible(server, PartySide.TENANT, CUTOFF)).isFalse();
    assertThat(RecordVisibility.visible(server, PartySide.TENANT, null)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionType.class,
      names = {
        "MOVE_OUT",
        "REQUEST_END",
        "CONFIRM_END",
        "REJECT_END",
        "CONFIRM_FINISH",
        "FINISH_ALONE",
        "AUTOCLOSE"
      })
  void 거주_종료_행동과_정산_완료는_공유_중단_뒤에도_넣는다(ActionType type) {
    assertThat(
            RecordVisibility.visible(
                entry(1, type, PartySide.LANDLORD, AFTER), PartySide.TENANT, CUTOFF))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionType.class,
      names = {"CONFIRM_LIVING", "LINK_VIEW", "RESEND_NOTICE", "RENOTIFY", "MARK_UNDELIVERED"})
  void 나만_보는_행동과_통지_상태는_상대_내려받기에_넣지_않는다(ActionType type) {
    ActionEntry theirs = entry(1, type, PartySide.LANDLORD, T0);

    assertThat(RecordVisibility.visible(theirs, PartySide.TENANT, null)).isFalse();
    assertThat(RecordVisibility.visible(theirs, PartySide.LANDLORD, null)).isTrue();
  }

  @Test
  void 확인_요청_전_상대_점검_초안은_빼고_내_초안은_넣는다() {
    UUID inspection = UUID.randomUUID();
    ActionEntry start = startInspection(1, inspection, PartySide.LANDLORD, T0);
    ActionEntry item = addItem(2, inspection, PartySide.LANDLORD, T0.plusSeconds(1));

    assertThat(RecordVisibility.select(List.of(start, item), PartySide.TENANT, null)).isEmpty();
    assertThat(RecordVisibility.select(List.of(start, item), PartySide.LANDLORD, null))
        .containsExactly(start, item);
  }

  @Test
  void 확인_요청을_보낸_점검은_상대에게도_넣는다() {
    UUID inspection = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    ActionEntry start = startInspection(1, inspection, PartySide.LANDLORD, T0);
    ActionEntry item = addItem(2, inspection, PartySide.LANDLORD, T0.plusSeconds(1));
    ActionEntry request =
        inspectionEntry(
            3,
            ActionType.REQUEST_INSPECTION_CONFIRM,
            PartySide.LANDLORD,
            ActionTarget.inspection(inspection),
            Map.of(),
            T0.plusSeconds(2));
    ActionEntry otherDraft = addItem(4, other, PartySide.LANDLORD, T0.plusSeconds(3));

    assertThat(
            RecordVisibility.select(
                List.of(start, item, request, otherDraft), PartySide.TENANT, null))
        .containsExactly(start, item, request);
  }

  @Test
  void 확인_요청이_공유_중단_뒤라_빠졌으면_그_초안도_뺀다() {
    UUID inspection = UUID.randomUUID();
    ActionEntry start = startInspection(1, inspection, PartySide.LANDLORD, T0);
    ActionEntry request =
        inspectionEntry(
            2,
            ActionType.REQUEST_INSPECTION_CONFIRM,
            PartySide.LANDLORD,
            ActionTarget.inspection(inspection),
            Map.of(),
            AFTER);

    assertThat(RecordVisibility.select(List.of(start, request), PartySide.TENANT, CUTOFF))
        .isEmpty();
  }

  @Test
  void 어느_점검인지_알_수_없는_상대_점검_행동은_뺀다() {
    ActionEntry photo =
        inspectionEntry(1, ActionType.ADD_PART_PHOTO, PartySide.LANDLORD, null, Map.of(), T0);

    assertThat(RecordVisibility.select(List.of(photo), PartySide.TENANT, null)).isEmpty();
  }

  private static ActionEntry startInspection(
      long seq, UUID inspection, PartySide side, Instant at) {
    return inspectionEntry(
        seq, ActionType.START_INSPECTION, side, ActionTarget.inspection(inspection), Map.of(), at);
  }

  private static ActionEntry addItem(long seq, UUID inspection, PartySide side, Instant at) {
    return inspectionEntry(
        seq,
        ActionType.ADD_ITEM,
        side,
        ActionTarget.of(TargetKind.INSPECTION_ITEM, UUID.randomUUID()),
        Map.of("inspectionId", inspection.toString()),
        at);
  }

  private static ActionEntry inspectionEntry(
      long seq,
      ActionType type,
      PartySide side,
      ActionTarget target,
      Map<String, Object> payload,
      Instant at) {
    return new ActionEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        seq,
        type,
        side,
        "member",
        "k" + seq,
        UUID.randomUUID(),
        target,
        null,
        null,
        null,
        payload,
        at);
  }

  private static ActionEntry entry(long seq, ActionType type, PartySide side, Instant at) {
    return new ActionEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        seq,
        type,
        side,
        side == null ? "server" : "member",
        "k" + seq,
        UUID.randomUUID(),
        ActionTarget.repairCase(UUID.randomUUID()),
        null,
        null,
        null,
        Map.of(),
        at);
  }
}
