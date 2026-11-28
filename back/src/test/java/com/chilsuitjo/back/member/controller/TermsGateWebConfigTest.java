package com.chilsuitjo.back.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.member.service.TermsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

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

  // 면제 주소가 /api/**에서 빠지는지(#338 리뷰 4). 경로는 06 4장 표와 같은 실제 주소 모양

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/auth/login",
        "/api/auth/logout",
        "/api/auth/refresh",
        "/api/auth/signup",
        "/api/public/links/view",
        "/api/me",
        "/api/me/agreements",
        "/api/me/password",
        "/api/me/withdraw",
        "/api/me/notifications/0190a3c4-0000-7000-8000-000000000001/read",
        "/api/rooms/0190a3c4-0000-7000-8000-000000000001/stop-sharing",
        "/api/photos/0190a3c4-0000-7000-8000-000000000001/erase",
        "/api/photos/view-urls"
      })
  void 면제_주소는_막기에_걸리지_않는다(String path) {
    assertThat(gated(path)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/rooms",
        "/api/me/phone",
        "/api/me/withdraw-preview",
        "/api/me/notifications/0190a3c4-0000-7000-8000-000000000001/unread",
        "/api/rooms/0190a3c4-0000-7000-8000-000000000001/upload-tickets",
        "/api/rooms/0190a3c4-0000-7000-8000-000000000001/stop-sharing/extra",
        "/api/photos/0190a3c4-0000-7000-8000-000000000001/redact",
        "/api/repair-cases/0190a3c4-0000-7000-8000-000000000001/respond"
      })
  void 면제가_아닌_주소는_막기에_걸린다(String path) {
    assertThat(gated(path)).isTrue();
  }

  private static boolean gated(String path) {
    MappedInterceptor mapped = TermsGateWebConfig.mapped(new TermsGateInterceptor(null));
    var request = new MockHttpServletRequest("POST", path);
    ServletRequestPathUtils.parseAndCache(request);
    return mapped.matches(request);
  }
}
