package com.chilsuitjo.back.member.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@code terms.gate.enabled=false}가 prod에 들어가도 막기가 조용히 꺼지지 않는다(CONS-4, #338 리뷰 3). */
class TermsServiceProdGuardTest {

  @Test
  void prod에서_막기를_끄면_시작하지_않는다() {
    var prod = new MockEnvironment();
    prod.setActiveProfiles("prod");

    assertThatThrownBy(() -> service(false, prod))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terms.gate.enabled");
  }

  @Test
  void prod에서_막기를_켜면_시작한다() {
    var prod = new MockEnvironment();
    prod.setActiveProfiles("prod");

    assertThatCode(() -> service(true, prod)).doesNotThrowAnyException();
  }

  @Test
  void prod가_아니면_막기를_끌_수_있다() {
    var test = new MockEnvironment();
    test.setActiveProfiles("test");

    assertThatCode(() -> service(false, test)).doesNotThrowAnyException();
  }

  private static TermsService service(boolean gateEnabled, MockEnvironment environment) {
    return new TermsService(null, null, null, null, gateEnabled, environment);
  }
}
