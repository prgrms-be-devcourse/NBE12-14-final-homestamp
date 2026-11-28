package com.chilsuitjo.back.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 공유 중단 뒤 점검 상태 다시 계산이 실패할 때 닫힌 쪽으로 가는지(불변식 18, #396). 지금 규칙에서는 공유를 그만둔 측만 막혀 이런 순서가 생기지 않으므로, 전이
 * 규칙이 특정 행동을 거절하도록 두고 행동 기록을 직접 넣어 본다.
 */
class InspectionSightOfTest {

  private static final Instant STARTED = Instant.parse("2026-11-20T00:00:00Z");
  private static final Instant AS_OF = Instant.parse("2026-11-21T00:00:00Z");

  private final InspectionTransitions rules = mock(InspectionTransitions.class);
  private final InspectionSight sight =
      new InspectionSight(
          mock(CounterpartReader.class),
          mock(ActionReader.class),
          rules,
          new InspectionItemTransitions(Duration.ofDays(7)),
          mock(InspectionItemVersionRepository.class),
          mock(WaitService.class));

  private final UUID room = UUID.randomUUID();
  private final UUID inspectionId = UUID.randomUUID();
  private Inspection row;

  /** 전이 규칙이 거절할 행동의 ID. */
  private Set<UUID> rejected = Set.of();

  @BeforeEach
  void setUp() {
    row = new Inspection(room, InspectionKind.MOVE_IN, PartySide.TENANT, false, STARTED);
    ReflectionTestUtils.setField(row, "id", inspectionId);
    // 지금 행: 고정 뒤 잠겼다(보는 사람에게 드러나면 안 되는 값)
    ReflectionTestUtils.setField(row, "status", InspectionStatus.LOCKED);
    when(rules.initial()).thenReturn(InspectionState.NONE);
    when(rules.apply(any(), any()))
        .thenAnswer(call -> {
          ActionEntry action = call.getArgument(1);
          if (rejected.contains(action.id())) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
          }
          InspectionStatus next =
              switch (action.type()) {
                case START_INSPECTION -> InspectionStatus.DRAFT;
                case REQUEST_INSPECTION_CONFIRM -> InspectionStatus.REQUESTED;
                default -> InspectionStatus.LOCKED;
              };
          return new InspectionState(
              InspectionKind.MOVE_IN,
              next,
              PartySide.TENANT,
              STARTED,
              null,
              null,
              null,
              null,
              null);
        });
  }

  @Test
  void 보는_측_행동을_더해_다시_계산할_수_없으면_기준_시각까지의_상태로_보고_지금_값은_내지_않는다() {
    ActionEntry own = entry(ActionType.LOCK, PartySide.TENANT, AS_OF.plusSeconds(60));
    rejected = Set.of(own.id());
    List<ActionEntry> recorded =
        List.of(
            entry(ActionType.START_INSPECTION, PartySide.TENANT, STARTED),
            entry(ActionType.REQUEST_INSPECTION_CONFIRM, PartySide.TENANT, STARTED.plusSeconds(60)),
            own);

    InspectionSight.Sight seen = sight.of(row, PartySide.TENANT, stopped(), recorded);

    assertThat(seen.shownStatus()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(seen.draft()).isFalse();
  }

  @Test
  void 볼_수_있는_점검인데_보이는_행동에_점검이_없으면_지금_값으로_돌아가지_않고_실패한다() {
    assertThatThrownBy(() -> sight.of(row, PartySide.LANDLORD, stopped(), List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 기준_시각까지의_기록마저_다시_계산할_수_없으면_실패한다() {
    ActionEntry start = entry(ActionType.START_INSPECTION, PartySide.TENANT, STARTED);
    rejected = Set.of(start.id());

    assertThatThrownBy(() -> sight.of(row, PartySide.LANDLORD, stopped(), List.of(start)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 공유를_그만둔_뒤_상대가_시작한_점검은_다시_계산하지_않고_가린다() {
    Inspection later =
        new Inspection(
            room, InspectionKind.MOVE_OUT, PartySide.TENANT, false, AS_OF.plusSeconds(60));
    ReflectionTestUtils.setField(later, "id", UUID.randomUUID());

    InspectionSight.Sight seen = sight.of(later, PartySide.LANDLORD, stopped(), List.of());

    assertThat(seen.hides(later)).isTrue();
    assertThat(seen.draftHidden()).isTrue();
    assertThat(seen.draft()).isTrue();
  }

  /** 임대인이 기준 시각에 공유를 그만둔 호실. 어느 측이 보든 기준 시각이 같다. */
  private static RoomParties stopped() {
    PartyFacts stopper =
        new PartyFacts(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PartyPresence.CONNECTED,
            AS_OF,
            false,
            false,
            null);
    return new RoomParties(RoomPhase.LIVING, stopper, stopper);
  }

  private ActionEntry entry(ActionType type, PartySide side, Instant at) {
    return new ActionEntry(
        UUID.randomUUID(),
        room,
        0,
        type,
        side,
        null,
        null,
        null,
        ActionTarget.inspection(inspectionId),
        null,
        null,
        null,
        null,
        at);
  }
}
