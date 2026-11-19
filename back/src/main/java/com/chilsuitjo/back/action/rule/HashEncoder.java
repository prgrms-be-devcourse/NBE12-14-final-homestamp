package com.chilsuitjo.back.action.rule;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 해시 입력을 바이트로 이어 붙인다(decisions 2026-11-18 해시 입력 형식, #72). 규칙은 하나다.
 *
 * <ul>
 *   <li>모든 칸은 {@code [길이 4바이트][값]}. 값이 없으면(NULL) 길이 자리에 {@code -1}을 쓰고 값은 없다
 *   <li>정수는 모두 8바이트 빅엔디언, UUID는 16바이트(앞 8바이트 + 뒤 8바이트), 글자는 UTF-8
 *   <li>시각은 1970-01-01 UTC부터의 마이크로초(정수). 마이크로초보다 작은 자리가 있으면 거절한다(DB가 반올림해 값이 달라지므로)
 *   <li>payload는 종류 1바이트를 붙여 재귀로 쓴다({@link #payload})
 * </ul>
 *
 * <p>이 클래스의 출력은 저장된 해시를 다시 계산하는 기준이라 <b>한 번 정하면 바꾸지 않는다</b>. 바꿀 일이 생기면 형식 버전을 올린다.
 */
final class HashEncoder {

  private static final int NULL_LENGTH = -1;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  HashEncoder nullField() {
    writeInt(NULL_LENGTH);
    return this;
  }

  HashEncoder bytes(byte[] value) {
    if (value == null) {
      return nullField();
    }
    writeInt(value.length);
    out.writeBytes(value);
    return this;
  }

  HashEncoder string(String value) {
    return value == null ? nullField() : bytes(value.getBytes(StandardCharsets.UTF_8));
  }

  HashEncoder int64(Long value) {
    return value == null ? nullField() : bytes(ByteBuffer.allocate(8).putLong(value).array());
  }

  HashEncoder int64(Integer value) {
    return int64(value == null ? null : Long.valueOf(value));
  }

  HashEncoder uuid(UUID value) {
    if (value == null) {
      return nullField();
    }
    return bytes(
        ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array());
  }

  HashEncoder micros(Instant value) {
    if (value == null) {
      return nullField();
    }
    if (value.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException("시각은 마이크로초로 자른 값만 받아요: " + value);
    }
    long micros =
        Math.addExact(
            Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1_000);
    return int64(micros);
  }

  /**
   * payload(jsonb)를 한 칸으로 쓴다. 안쪽 값은 {@code [종류 1바이트][몸통]}이다.
   *
   * <ul>
   *   <li>{@code N} 없음: 몸통 없음
   *   <li>{@code S} 글자: {@code [길이][UTF-8]}. enum은 {@code name()}을 글자로 쓴다(jsonb에 글자로 저장되므로)
   *   <li>{@code I} 정수: {@code [길이=8][8바이트]}. long 범위만. 소수는 거절한다
   *   <li>{@code B} 참거짓: {@code [길이=1][0 또는 1]}
   *   <li>{@code O} 객체: {@code [항목 수 4바이트]} 뒤에 키의 UTF-8 바이트 순으로 {@code [키 길이][키][값]}
   *   <li>{@code L} 목록: {@code [항목 수 4바이트]} 뒤에 순서대로 {@code [값]}
   * </ul>
   */
  HashEncoder payload(Object value) {
    if (value == null) {
      return nullField();
    }
    HashEncoder inner = new HashEncoder();
    inner.value(value);
    return bytes(inner.out.toByteArray());
  }

  byte[] sha256() {
    try {
      return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256은 모든 JVM에 있어요", impossible);
    }
  }

  /** 테스트에서 입력 바이트를 직접 볼 때만 쓴다. */
  byte[] toByteArray() {
    return out.toByteArray();
  }

  private void value(Object value) {
    switch (value) {
      case null -> out.write('N');
      case String s -> tagged('S').string(s);
      case Enum<?> e -> tagged('S').string(e.name());
      case Boolean b -> tagged('B').bytes(new byte[] {(byte) (b ? 1 : 0)});
      case Long l -> tagged('I').int64(l);
      case Integer i -> tagged('I').int64(i);
      case Short s -> tagged('I').int64((long) s);
      case Byte b -> tagged('I').int64((long) b);
      case BigInteger big -> tagged('I').int64(exactLong(new BigDecimal(big)));
      // 2.0처럼 정수 모양이어도 소수 자리가 있으면 거절한다. jsonb는 2.0을 그대로 두어, 읽는 쪽이 Double로 읽으면 어긋난다.
      case BigDecimal decimal when decimal.scale() > 0 -> throw decimal(decimal);
      case BigDecimal decimal -> tagged('I').int64(exactLong(decimal));
      case Double d -> throw decimal(d);
      case Float f -> throw decimal(f);
      case Map<?, ?> map -> object(map);
      case List<?> list -> {
        out.write('L');
        writeInt(list.size());
        list.forEach(this::value);
      }
      default ->
          throw new IllegalArgumentException(
              "payload에 쓸 수 없는 값이에요(글자·정수·참거짓·없음·객체·목록만): " + value.getClass().getName());
    }
  }

  private void object(Map<?, ?> map) {
    out.write('O');
    writeInt(map.size());
    List<byte[]> keys = new ArrayList<>();
    for (Object key : map.keySet()) {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException("payload 객체의 키는 글자만 돼요: " + key);
      }
      keys.add(text.getBytes(StandardCharsets.UTF_8));
    }
    keys.sort(Arrays::compareUnsigned);
    for (byte[] key : keys) {
      bytes(key);
      value(map.get(new String(key, StandardCharsets.UTF_8)));
    }
  }

  private HashEncoder tagged(char tag) {
    out.write(tag);
    return this;
  }

  private static long exactLong(BigDecimal number) {
    try {
      return number.longValueExact();
    } catch (ArithmeticException notWholeOrTooBig) {
      throw new IllegalArgumentException(
          "payload 숫자는 long 범위의 정수만 돼요(소수 금지): " + number, notWholeOrTooBig);
    }
  }

  private static IllegalArgumentException decimal(Number number) {
    return new IllegalArgumentException("payload 숫자는 정수만 돼요(소수 금지): " + number);
  }

  private void writeInt(int value) {
    out.writeBytes(ByteBuffer.allocate(4).putInt(value).array());
  }
}
