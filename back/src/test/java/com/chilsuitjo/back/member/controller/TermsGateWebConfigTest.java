package com.chilsuitjo.back.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.member.service.TermsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * {@link TermsGateWebConfig}: {@link TermsService}가 빠져도 약관 확인 전 막기가 조용히 꺼지지 않는다(CONS-4). 걸린 막기가 실제로
 * 막는지는 {@code TermsGateApiTest}가 앱 전체로 본다.
 */
class TermsGateWebConfigTest {

  @Test
  void 앱으로_띄웠는데_TermsService가_없으면_시작하지_않는다() {
    var config =
        new TermsGateWebConfig(
            new StaticListableBeanFactory().getBeanProvider(TermsService.class),
            new MockEnvironment());

    assertThatThrownBy(() -> config.addInterceptors(new InterceptorRegistry()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TermsService");
  }

  @Test
  void 자동_설정을_끈_테스트_범위에서만_막기를_걸지_않고_넘어간다() {
    var environment =
        new MockEnvironment().withProperty("spring.boot.enableautoconfiguration", "false");
    var config =
        new TermsGateWebConfig(
            new StaticListableBeanFactory().getBeanProvider(TermsService.class), environment);

    config.addInterceptors(new InterceptorRegistry());

    assertThat(TermsGateWebConfig.isSliceTest(environment)).isTrue();
    assertThat(TermsGateWebConfig.isSliceTest(new MockEnvironment())).isFalse();
  }
}
