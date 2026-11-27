package com.chilsuitjo.back.member.controller;

import com.chilsuitjo.back.member.service.TermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link TermsGateInterceptor}를 {@code /api/**}에 건다. 확인 전에도 받는 주소는 {@link
 * TermsGateInterceptor#EXEMPT}.
 *
 * <p>{@link TermsService}가 없으면 앱이 시작하지 않는다(막기가 조용히 빠지지 않게). 예외는 자동 설정을 끈 테스트 범위({@code @WebMvcTest}
 * 등, {@code spring.boot.enableautoconfiguration=false})뿐이다. 그 범위는 이 설정은 읽지만 서비스 빈을 띄우지 않는다.
 */
@Configuration
@RequiredArgsConstructor
class TermsGateWebConfig implements WebMvcConfigurer {

  private final ObjectProvider<TermsService> terms;
  private final Environment environment;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    TermsService service = terms.getIfAvailable();
    if (service == null) {
      if (isSliceTest(environment)) {
        return;
      }
      throw new IllegalStateException("TermsService가 없어 약관 확인 전 막기(CONS-4)를 걸 수 없다");
    }
    registry
        .addInterceptor(new TermsGateInterceptor(service))
        .addPathPatterns("/api/**")
        .excludePathPatterns(TermsGateInterceptor.EXEMPT);
  }

  /** 자동 설정을 끈 테스트 범위인지. 앱으로 띄우면 이 값이 없다(기본 켬). */
  static boolean isSliceTest(Environment environment) {
    return !environment.getProperty(
        EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
  }
}
