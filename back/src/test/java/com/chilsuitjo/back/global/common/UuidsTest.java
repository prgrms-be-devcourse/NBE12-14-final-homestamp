package com.chilsuitjo.back.global.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

  @Test
  void 버전_7과_RFC_변형_비트를_갖는다() {
    UUID id = Uuids.v7();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void 앞_48비트가_밀리초_시각이다() {
    long millis = 1_763_424_000_000L; // 2025-11-18T00:00:00Z

    UUID id = Uuids.v7(millis);

    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
  }

  @Test
  void 시각이_늦으면_뒤로_정렬된다() {
    UUID earlier = Uuids.v7(1_000L);
    UUID later = Uuids.v7(1_001L);

    // PostgreSQL uuid 정렬과 같은 부호 없는 비교
    assertThat(
            Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits()))
        .isNegative();
  }

  @Test
  void 같은_밀리초에도_겹치지_않는다() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(Uuids.v7(1_000L));
    }

    assertThat(ids).hasSize(10_000);
  }
}
