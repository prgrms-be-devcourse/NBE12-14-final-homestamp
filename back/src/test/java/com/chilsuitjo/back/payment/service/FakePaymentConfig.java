package com.chilsuitjo.back.payment.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 가짜 지불 창구를 쓰는 테스트용 설정(임시). 기준 금액을 {@code seedConfirmedTotal}로 적어 쓰는 테스트가 실제 [맞아요] 없이 돌게 한다. 응답이
 * 들어온 뒤 테스트를 실제 구현({@code PaymentFixtures})으로 옮기면서 지운다.
 */
@TestConfiguration
public class FakePaymentConfig {

  @Bean
  @Primary
  FakePaymentService fakePaymentService() {
    return new FakePaymentService();
  }
}
