package com.chilsuitjo.back.global.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 앱이 직접 만드는 UUID v7(RFC 9562). 일반 엔티티는 Hibernate가 저장할 때 만들고, 서로를 가리키는 머리·판 네 쌍만 이 함수로 저장 전에 만든다(04
 * 기본 키, 05 0장 {@code id}).
 *
 * <p>앞 48비트는 밀리초 시각이라 시간순으로 정렬되고, 나머지 74비트는 난수라 번호를 짐작할 수 없다. 같은 밀리초 안의 순서는 보장하지 않는다.
 */
public final class Uuids {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Uuids() {}

  public static UUID v7() {
    return v7(System.currentTimeMillis());
  }

  /** 시각을 정해 만든다(테스트용). */
  static UUID v7(long epochMillis) {
    long msb = (epochMillis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
    long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }
}
