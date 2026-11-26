package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmsModeTest {

  @Test
  void 소문자_값만_읽는다() {
    assertThat(SmsMode.parse("fake")).contains(SmsMode.FAKE);
    assertThat(SmsMode.parse("disabled")).contains(SmsMode.DISABLED);
    assertThat(SmsMode.parse("solapi")).contains(SmsMode.SOLAPI);
  }

  @Test
  void 모르는_값은_다른_방식으로_바꾸지_않는다() {
    assertThat(SmsMode.parse("FAKE")).isEmpty();
    assertThat(SmsMode.parse(" disabled")).isEmpty();
    assertThat(SmsMode.parse("off")).isEmpty();
    assertThat(SmsMode.parse(null)).isEmpty();
  }
}
