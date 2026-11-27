package com.chilsuitjo.back.notification.service;

import java.time.Duration;

/** 기다림 설정값(06 0-1 {@code wait.*}) 확인. 기한은 DB에 마이크로초로 저장하므로 그 단위여야 한다. */
final class WaitDurations {

  private WaitDurations() {}

  static Duration require(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero() || value.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(name + "는 0보다 크고 마이크로초 단위여야 해요");
    }
    return value;
  }
}
