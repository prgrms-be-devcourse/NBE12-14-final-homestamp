package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 저장된(또는 저장하려는) 행동 한 줄. {@code apply}의 입력이고 경과 보기(REP-11)·재계산에도 쓴다.
 *
 * @param id 저장 전 미리 보기(pipeline이 {@code apply}에 넘길 때)면 null
 * @param roomSeq 저장 전이면 0
 * @param idempotencyKey 이 행동의 멱등 키. 요청 주체의 행동은 {@code <키>}, {@code <키>#2} …, 딸린 행동은 {@code
 *     <requestId>#n}(09 3-1). 저장 전이면 null
 * @param requestId 한 요청으로 남긴 행동을 묶는 값. 한 요청의 모든 행동이 같은 값이다(#66). 저장 전이면 null
 * @param createdAt 서버 시각. {@code apply}는 {@code Clock}을 부르지 않으므로 시각은 여기서 읽는다(09 2장 {@code rule/})
 */
public record ActionEntry(
    UUID id,
    UUID roomRecordId,
    long roomSeq,
    ActionType type,
    PartySide actorSide,
    String actorKey,
    String idempotencyKey,
    UUID requestId,
    ActionTarget target,
    Integer seenVersionNo,
    byte[] contentDigest,
    String reason,
    Map<String, Object> payload,
    Instant createdAt) {

  public ActionEntry {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  /**
   * {@code payload}를 도메인의 입력 record로 바꿔 읽는다(09 3-1, decisions 2026-11-17). 행동 기록에는 {@code payload}가
   * 그대로 남는다.
   *
   * <pre>{@code
   * record RespondPayload(Answer answer) {}
   * Answer answer = entry.payloadAs(RespondPayload.class).answer();
   * }</pre>
   *
   * <p>record의 칸 이름과 같은 키를 찾는다. 값이 이미 그 타입이면 그대로, 글자면 enum·UUID로, 숫자면 그 숫자 타입으로 바꾼다. 없는 키는 null(기본
   * 타입이면 오류).
   *
   * @throws IllegalArgumentException record가 아니거나 값을 바꿀 수 없을 때
   */
  public <T extends Record> T payloadAs(Class<T> type) {
    RecordComponent[] components = type.getRecordComponents();
    if (components == null) {
      throw new IllegalArgumentException(type.getName() + "은 record가 아니에요");
    }
    Object[] args = new Object[components.length];
    Class<?>[] types = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      args[i] = convert(components[i].getName(), payload.get(components[i].getName()), types[i]);
    }
    try {
      Constructor<T> constructor = type.getDeclaredConstructor(types);
      constructor.setAccessible(true);
      return constructor.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException(type.getName() + "을 만들 수 없어요", e);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object convert(String name, Object value, Class<?> target) {
    if (value == null) {
      if (target.isPrimitive()) {
        throw new IllegalArgumentException("payload에 " + name + "이 없어요");
      }
      return null;
    }
    if (target.isInstance(value) || boxed(target).isInstance(value)) {
      return value;
    }
    if (value instanceof String text) {
      if (target.isEnum()) {
        return Enum.valueOf((Class<? extends Enum>) target, text);
      }
      if (target == UUID.class) {
        return UUID.fromString(text);
      }
    }
    if (value instanceof Number number) {
      Class<?> box = boxed(target);
      if (box == Long.class) {
        return number.longValue();
      }
      if (box == Integer.class) {
        return number.intValue();
      }
    }
    throw new IllegalArgumentException(
        "payload의 "
            + name
            + "("
            + value.getClass().getSimpleName()
            + ")을 "
            + target.getSimpleName()
            + "로 바꿀 수 없어요");
  }

  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    return type;
  }
}
