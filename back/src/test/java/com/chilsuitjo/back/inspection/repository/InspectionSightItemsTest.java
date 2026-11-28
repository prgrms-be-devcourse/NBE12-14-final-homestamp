package com.chilsuitjo.back.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 공유 중단 뒤 항목 응답 다시 계산이 실패할 때 닫힌 쪽으로 가는지(불변식 18, #397 리뷰). 지금 규칙에서는 공유를 그만둔 측만 막혀 이런 순서가 생기지 않으므로,
 * 행동 기록을 직접 넣어 본다.
 */
class InspectionSightItemsTest {

  private static final Instant ADDED = Instant.parse("2026-11-20T00:00:00Z");
  private static final Instant AS_OF = Instant.parse("2026-11-21T00:00:00Z");

  private final InspectionItemVersionRepository versions =
      mock(InspectionItemVersionRepository.class);
  private final InspectionSight sight =
      new InspectionSight(
          mock(CounterpartReader.class),
          mock(ActionReader.class),
          mock(InspectionTransitions.class),
          new InspectionItemTransitions(Duration.ofDays(7)),
          versions,
          mock(WaitService.class));

  private final UUID room = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final InspectionItem item =
      new InspectionItem(
          itemId, room, UUID.randomUUID(), versionId, PartySide.TENANT, false, false, ADDED);

  @Test
  void 보는_측_행동을_더해_다시_계산할_수_없으면_기준_시각까지의_행동으로_보고_지금_값은_내지_않는다() {
    // 지금 행: 기준 시각 뒤 상대가 이견을 남겼다(보는 사람에게 드러나면 안 되는 값)
    item.changeStatus(AckState.DISPUTE);
    givenVersions();
    InspectionSight.Sight tenant = sight(PartySide.TENANT);
    // 기준 시각 전: 상대가 보류. 뒤: 보는 측 보충(확인 기다림) → 상대 이견(가림) → 보는 측 다시 보충.
    // 가린 이견을 빼면 확인 기다림에서 다시 보충하게 되어 전이 규칙이 거절한다
    List<ActionEntry> recorded =
        List.of(
            entry(ActionType.ADD_ITEM, PartySide.TENANT, ADDED, Map.of()),
            entry(
                ActionType.RESPOND_ITEM,
                PartySide.LANDLORD,
                ADDED.plusSeconds(60),
                Map.of("answer", "NEED_MORE")),
            entry(ActionType.SUPPLEMENT_ITEM, PartySide.TENANT, AS_OF.plusSeconds(60), Map.of()),
            entry(
                ActionType.RESPOND_ITEM,
                PartySide.LANDLORD,
                AS_OF.plusSeconds(120),
                Map.of("answer", "DISPUTE")),
            entry(
                ActionType.SUPPLEMENT_ITEM,
                PartySide.TENANT,
                AS_OF.plus(Duration.ofDays(8)),
                Map.of()));

    InspectionSight.ItemSight seen = sight.items(List.of(item), tenant, recorded).get(itemId);

    // 지금 값(이견)도, 성공했다면 나왔을 확인 기다림도 아닌, 기준 시각까지의 보류
    assertThat(seen.status()).isEqualTo(AckState.NEED_MORE);
    assertThat(seen.version().getNo()).isEqualTo(1);
  }

  @Test
  void 기준_시각까지_항목이_없으면_지금_값으로_돌아가지_않고_실패한다() {
    item.changeStatus(AckState.ACK);
    givenVersions();
    InspectionSight.Sight landlord = sight(PartySide.LANDLORD);

    assertThatThrownBy(() -> sight.items(List.of(item), landlord, List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 기준_시각까지의_기록마저_다시_계산할_수_없으면_실패한다() {
    givenVersions();
    InspectionSight.Sight landlord = sight(PartySide.LANDLORD);
    // 같은 항목을 두 번 더한 기록(어긋난 기록)
    List<ActionEntry> broken =
        List.of(
            entry(ActionType.ADD_ITEM, PartySide.TENANT, ADDED, Map.of()),
            entry(ActionType.ADD_ITEM, PartySide.TENANT, ADDED, Map.of()));

    assertThatThrownBy(() -> sight.items(List.of(item), landlord, broken))
        .isInstanceOf(IllegalStateException.class);
  }

  private void givenVersions() {
    when(versions.findByInspectionItemIdInOrderByInspectionItemIdAscNoAsc(anyCollection()))
        .thenReturn(
            List.of(
                new InspectionItemVersion(
                    versionId, room, itemId, 1, UUID.randomUUID(), "벽지 들뜸", null, ADDED)));
  }

  private static InspectionSight.Sight sight(PartySide viewer) {
    return new InspectionSight.Sight(viewer, AS_OF, InspectionStatus.REQUESTED, false, false);
  }

  private ActionEntry entry(
      ActionType type, PartySide side, Instant at, Map<String, Object> payload) {
    return new ActionEntry(
        UUID.randomUUID(),
        room,
        0,
        type,
        side,
        null,
        null,
        null,
        ActionTarget.of(TargetKind.INSPECTION_ITEM, itemId),
        type == ActionType.RESPOND_ITEM ? 1 : null,
        null,
        type == ActionType.RESPOND_ITEM ? "사실과 달라요" : null,
        payload,
        at);
  }
}
