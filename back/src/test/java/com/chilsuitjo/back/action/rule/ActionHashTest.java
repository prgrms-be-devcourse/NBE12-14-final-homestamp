package com.chilsuitjo.back.action.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 행동 해시 입력 형식을 고정한다(#72). 벡터의 기대값은 같은 규칙을 Java와 따로 구현해 계산한 값이다. 이 테스트가 깨지면 저장된 해시를 다시 계산할 수 없게 되므로,
 * 형식을 바꾸려면 테스트를 고치지 말고 형식 버전을 올린다.
 */
class ActionHashTest {

  static final UUID ROOM = UUID.fromString("0193a000-0000-7000-8000-000000000001");
  static final UUID MEMBER = UUID.fromString("0193a000-0000-7000-8000-000000000002");
  static final UUID REPAIR = UUID.fromString("0193a000-0000-7000-8000-000000000003");
  static final UUID REQUEST = UUID.fromString("0193a000-0000-7000-8000-000000000004");
  static final Instant AT = Instant.parse("2026-11-18T05:00:00.123456Z");

  static ActionHash.Input sample() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("while_open_objection", "NEED_MORE");
    payload.put("n", 3);
    payload.put("ok", true);
    payload.put("list", Arrays.asList(1, "a", null));
    return new ActionHash.Input(
        1,
        ROOM,
        1,
        ActionHash.start(),
        filled(0x11),
        MEMBER,
        PartySide.TENANT,
        MEMBER.toString(),
        "REGISTER",
        "k1",
        null,
        null,
        payload,
        "repair_case_id",
        REPAIR,
        AT,
        REQUEST);
  }

  @Test
  void 테스트_벡터_내용_대상이_있는_회원_행동() {
    assertThat(hex(ActionHash.compute(sample())))
        .isEqualTo("b2f67879f005ac2fbb809fd5db0cb9fa6e8f3d5a9b27f5d1ee965a04ba400e35");
  }

  @Test
  void 테스트_벡터_대상_없는_서버_행동() {
    ActionHash.Input roomOnly =
        new ActionHash.Input(
            1,
            ROOM,
            2,
            filled(0x22),
            null,
            null,
            null,
            "server",
            "AUTOCLOSE",
            REQUEST + "#2",
            null,
            "",
            null,
            null,
            null,
            AT,
            REQUEST);

    assertThat(hex(ActionHash.compute(roomOnly)))
        .isEqualTo("8859a54d72c584dee83b4a1f4c8e601f6c33a4c0054c7f7206d0d8adf9db8abd");
  }

  @Test
  void 첫_행동의_시작값은_32바이트_0() {
    assertThat(ActionHash.start()).hasSize(32).containsOnly(0);
  }

  @Test
  void 칸_하나만_달라도_해시가_달라진다() {
    ActionHash.Input base = sample();
    byte[] baseHash = ActionHash.compute(base);
    UUID other = UUID.fromString("0193a000-0000-7000-8000-0000000000ff");
    List<UnaryOperator<ActionHash.Input>> changes = new ArrayList<>();
    changes.add(i -> with(i, "roomRecordId", other));
    changes.add(i -> with(i, "roomSeq", 2L));
    changes.add(i -> with(i, "prevHash", filled(0x01)));
    changes.add(i -> with(i, "contentDigest", filled(0x12)));
    changes.add(i -> with(i, "actorMemberId", other));
    changes.add(i -> with(i, "actorSide", PartySide.LANDLORD));
    changes.add(i -> with(i, "actorKey", "server"));
    changes.add(i -> with(i, "type", "SUPPLEMENT"));
    changes.add(i -> with(i, "idempotencyKey", "k2"));
    changes.add(i -> with(i, "seenVersionNo", 1));
    changes.add(i -> with(i, "reason", "사유"));
    changes.add(i -> with(i, "payload", Map.of("while_open_objection", "DISPUTE")));
    changes.add(i -> with(i, "targetColumn", "payment_id"));
    changes.add(i -> with(i, "targetId", other));
    changes.add(i -> with(i, "createdAt", AT.plusNanos(1_000)));
    changes.add(i -> with(i, "requestId", other));

    for (UnaryOperator<ActionHash.Input> change : changes) {
      assertThat(ActionHash.compute(change.apply(base))).isNotEqualTo(baseHash);
    }
    assertThat(changes).hasSize(ActionHash.Input.class.getRecordComponents().length - 1);
  }

  @Test
  void 없음과_빈_글자는_다르다() {
    assertThat(ActionHash.compute(with(sample(), "reason", null)))
        .isNotEqualTo(ActionHash.compute(with(sample(), "reason", "")));
    assertThat(ActionHash.compute(with(sample(), "payload", null)))
        .isNotEqualTo(ActionHash.compute(with(sample(), "payload", Map.of())));
  }

  @Test
  void 칸_경계가_바뀌면_다르다() {
    ActionHash.Input a = with(with(sample(), "actorKey", "ab"), "type", "c");
    ActionHash.Input b = with(with(sample(), "actorKey", "a"), "type", "bc");
    assertThat(ActionHash.compute(a)).isNotEqualTo(ActionHash.compute(b));
  }

  @Test
  void payload_글자_1과_숫자_1은_다르다() {
    assertThat(ActionHash.compute(with(sample(), "payload", Map.of("n", "1"))))
        .isNotEqualTo(ActionHash.compute(with(sample(), "payload", Map.of("n", 1))));
  }

  @Test
  void payload_객체의_키_순서는_상관없고_목록_순서는_상관있다() {
    Map<String, Object> ab = new LinkedHashMap<>();
    ab.put("a", 1);
    ab.put("b", Map.of("y", 2, "x", 1));
    Map<String, Object> ba = new LinkedHashMap<>();
    ba.put("b", Map.of("x", 1, "y", 2));
    ba.put("a", 1);

    assertThat(ActionHash.compute(with(sample(), "payload", ab)))
        .isEqualTo(ActionHash.compute(with(sample(), "payload", ba)));
    assertThat(ActionHash.compute(with(sample(), "payload", List.of(1, 2))))
        .isNotEqualTo(ActionHash.compute(with(sample(), "payload", List.of(2, 1))));
  }

  @Test
  void payload_정수는_자료형과_상관없이_같다() {
    byte[] asLong = ActionHash.compute(with(sample(), "payload", Map.of("n", 3L)));
    assertThat(ActionHash.compute(with(sample(), "payload", Map.of("n", 3)))).isEqualTo(asLong);
    assertThat(ActionHash.compute(with(sample(), "payload", Map.of("n", new BigDecimal("3")))))
        .isEqualTo(asLong);
  }

  @Test
  void payload_enum은_이름_글자와_같다() {
    assertThat(ActionHash.compute(with(sample(), "payload", Map.of("side", PartySide.TENANT))))
        .isEqualTo(ActionHash.compute(with(sample(), "payload", Map.of("side", "TENANT"))));
  }

  @Test
  void payload_소수는_거절한다() {
    for (Object decimal : List.of(1.5, 2.0, 1.5f, new BigDecimal("1.5"))) {
      assertThatThrownBy(() -> ActionHash.compute(with(sample(), "payload", Map.of("n", decimal))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("소수 금지");
    }
  }

  @Test
  void payload에_쓸_수_없는_값은_거절한다() {
    assertThatThrownBy(
            () -> ActionHash.compute(with(sample(), "payload", Map.of("at", Instant.now()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "payload", Map.of(1, "a"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 마이크로초보다_작은_자리가_있는_시각은_거절한다() {
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "createdAt", AT.plusNanos(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("마이크로초");
  }

  @Test
  void 잘못된_입력은_거절한다() {
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "hashVersion", 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "prevHash", new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "prevHash", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "contentDigest", new byte[33])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "targetColumn", "room_id")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActionHash.compute(with(sample(), "targetId", null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 대상_칸_목록은_V1_칸_순서와_같다() throws Exception {
    String sql;
    try (var in = getClass().getResourceAsStream("/db/migration/V1__init_schema.sql")) {
      sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String table = sql.substring(sql.indexOf("CREATE TABLE record.action ("));
    table = table.substring(0, table.indexOf("CONSTRAINT"));
    List<String> inV1 =
        java.util.regex.Pattern.compile("(?m)^\\s+([a-z_]+_id)\\s+uuid,")
            .matcher(table)
            .results()
            .map(m -> m.group(1))
            .toList();

    assertThat(inV1).containsExactlyElementsOf(ActionHash.TARGET_COLUMNS);
  }

  /** 레코드 칸 하나만 바꾼 복사본. */
  static ActionHash.Input with(ActionHash.Input in, String component, Object value) {
    try {
      var components = ActionHash.Input.class.getRecordComponents();
      Object[] args = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        types[i] = components[i].getType();
        args[i] =
            components[i].getName().equals(component)
                ? value
                : components[i].getAccessor().invoke(in);
      }
      return ActionHash.Input.class.getDeclaredConstructor(types).newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] filled(int b) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) b);
    return bytes;
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
