package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.entity.ActionRecord;
import com.chilsuitjo.back.action.rule.ActionHash;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 대상 17칸은 세 곳에 있다: {@link TargetKind}, {@link ActionHash#TARGET_COLUMNS}(해시 입력 순서), {@link
 * ActionRecord#target}의 칸 이름. 칸을 더하거나 이름을 바꿀 때 한 곳만 고치면 여기서 떨어진다(#87 리뷰).
 */
class ActionTargetColumnTest {

  @Test
  void 대상_종류와_해시_대상_칸은_하나씩_맞는다() {
    var columns =
        Arrays.stream(TargetKind.values())
            .filter(kind -> kind != TargetKind.ROOM_RECORD)
            .map(kind -> JpaActionWriter.columnOf(ActionTarget.of(kind, UUID.randomUUID())))
            .toList();

    assertThat(columns).containsExactlyInAnyOrderElementsOf(ActionHash.TARGET_COLUMNS);
    assertThat(JpaActionWriter.columnOf(ActionTarget.roomRecord(UUID.randomUUID()))).isNull();
    assertThat(JpaActionWriter.columnOf(null)).isNull();
  }

  @Test
  void 행동_기록의_대상_칸은_해시_대상_칸과_이름이_같다() {
    for (String column : ActionHash.TARGET_COLUMNS) {
      UUID id = UUID.randomUUID();
      ActionRecord record = sample().target(column, id);

      assertThat(record.targetColumn()).as(column).isEqualTo(column);
      assertThat(record.targetId()).as(column).isEqualTo(id);
    }
    assertThat(sample().targetColumn()).isNull();
  }

  private static ActionRecord sample() {
    return new ActionRecord(
        ActionHash.VERSION,
        UUID.randomUUID(),
        1,
        ActionHash.start(),
        new byte[32],
        null,
        null,
        PartySide.TENANT,
        "server",
        "AUTOCLOSE",
        "k",
        UUID.randomUUID(),
        null,
        null,
        null,
        Instant.EPOCH);
  }
}
