package com.chilsuitjo.back.member.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ROOM-2c: 이름은 같은 규칙으로 가린다. 예는 06 ROOM-2c 표 그대로다. */
class NameMaskTest {

  @ParameterizedTest
  @CsvSource({"정, 정", "정윤, 정*", "정서윤, 정*윤", "남궁민수, 남**수", "'John Kim', 'J*** **m'"})
  void 이름을_가린다(String name, String masked) {
    assertThat(NameMask.mask(name)).isEqualTo(masked);
  }
}
