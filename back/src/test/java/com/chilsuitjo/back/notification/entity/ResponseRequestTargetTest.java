package com.chilsuitjo.back.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 응답 요청의 대상 변환(#117 2단계). DB 없이 "어느 종류가 어느 V1 칸에 들어가는가"만 본다. 칸 이름은 필드의 {@code @Column}에서 읽어, 필드와 칸
 * 이름이 뒤바뀐 실수(둘 다 uuid라 {@code ddl-auto: validate}는 못 잡음)도 여기서 걸린다.
 *
 * <p>실제 INSERT와 FK·CHECK는 {@code ResponseRequestEntityTest}(JPA 왕복)와 V1 SQL 테스트({@code
 * ResponseRequestSchemaTest} 등)가 맡는다.
 */
class ResponseRequestTargetTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");
  private static final UUID ROOM = UUID.randomUUID();

  /** V1 {@code state.response_request}의 대상 칸(05 2-4). 호실 기록은 칸이 없다. */
  private static final Map<TargetKind, String> COLUMN =
      Map.of(
          TargetKind.REPAIR_CASE, "repair_case_id",
          TargetKind.PAYMENT, "payment_id",
          TargetKind.SETTLEMENT_ITEM, "settlement_item_id",
          TargetKind.SETTLEMENT, "settlement_id",
          TargetKind.INSPECTION, "inspection_id",
          TargetKind.INSPECTION_ITEM, "inspection_item_id",
          TargetKind.SETTLEMENT_TRANSFER, "settlement_transfer_id",
          TargetKind.PROGRESS_NOTE, "progress_note_id");

  @ParameterizedTest
  @EnumSource(
      value = TargetKind.class,
      names = {
        "REPAIR_CASE",
        "PAYMENT",
        "SETTLEMENT_ITEM",
        "SETTLEMENT",
        "INSPECTION",
        "INSPECTION_ITEM",
        "SETTLEMENT_TRANSFER",
        "PROGRESS_NOTE"
      })
  void 칸이_있는_대상은_그_칸_하나에만_들어가고_그대로_돌아온다(TargetKind kind) {
    UUID id = UUID.randomUUID();
    RequestTarget target = RequestTarget.of(kind, id);

    ResponseRequest request = request(ROOM, target);

    Map<String, UUID> filled = filledTargetColumns(request);
    assertThat(filled).containsExactly(Map.entry(COLUMN.get(kind), id));
    assertThat(request.target()).isEqualTo(target);
  }

  @Test
  void 호실_기록_대상은_대상_칸을_모두_비우고_그대로_돌아온다() {
    ResponseRequest request = request(ROOM, RequestTarget.roomRecord(ROOM));

    assertThat(filledTargetColumns(request)).isEmpty();
    assertThat(request.target()).isEqualTo(RequestTarget.roomRecord(ROOM));
  }

  @Test
  void 호실_기록_대상의_ID가_이_요청의_호실과_다르면_거절한다() {
    // 칸이 없어 그 ID는 저장되지 않는다. 받아 주면 다른 호실을 가리킨 요청이 이 호실 요청으로 남는다
    RequestTarget otherRoom = RequestTarget.roomRecord(UUID.randomUUID());

    assertThatThrownBy(() -> request(ROOM, otherRoom)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = TargetKind.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {
        "ROOM_RECORD",
        "REPAIR_CASE",
        "PAYMENT",
        "SETTLEMENT_ITEM",
        "SETTLEMENT",
        "INSPECTION",
        "INSPECTION_ITEM",
        "SETTLEMENT_TRANSFER",
        "PROGRESS_NOTE"
      })
  void V1에_칸이_없는_대상은_거절한다(TargetKind kind) {
    RequestTarget target = RequestTarget.of(kind, UUID.randomUUID());

    assertThatThrownBy(() -> request(ROOM, target))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(kind.name());
  }

  @Test
  void 받는_대상과_거절하는_대상을_합치면_TargetKind_전부다() {
    // 위 두 목록이 빠짐없이 나뉘었는지(받는 9 + 거절 9). TargetKind가 늘면 엔티티 switch가 컴파일되지 않고 이 수도 바뀐다
    assertThat(COLUMN).hasSize(8);
    assertThat(TargetKind.values()).hasSize(18);
  }

  private static ResponseRequest request(UUID room, RequestTarget target) {
    return new ResponseRequest(room, RequestKind.DEFECT_CONFIRM, target, PartySide.TENANT, NOW);
  }

  /** 대상 칸 여덟 개 가운데 값이 든 것만 {칸 이름 → 값}으로 모은다. 칸 이름은 필드의 {@code @Column}에서 읽는다. */
  private static Map<String, UUID> filledTargetColumns(ResponseRequest request) {
    Map<String, UUID> filled = new HashMap<>();
    for (Field field : ResponseRequest.class.getDeclaredFields()) {
      Column column = field.getAnnotation(Column.class);
      if (column == null || !COLUMN.containsValue(column.name())) {
        continue;
      }
      field.setAccessible(true);
      try {
        Object value = field.get(request);
        if (value != null) {
          filled.put(column.name(), (UUID) value);
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    // 대상 칸 여덟 개가 모두 엔티티에 있어야 한다(하나라도 빠지면 그 종류는 저장할 곳이 없음)
    assertThat(
            Arrays.stream(ResponseRequest.class.getDeclaredFields())
                .map(f -> f.getAnnotation(Column.class))
                .filter(c -> c != null && COLUMN.containsValue(c.name()))
                .count())
        .isEqualTo(8);
    return filled;
  }
}
