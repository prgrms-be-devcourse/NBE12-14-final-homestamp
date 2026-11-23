package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 프로필마다 토큰 검증기가 켜지는지, {@code prod}가 키 없이 시작을 거부하는지 본다. DB 없이 보안 설정만 띄우고, 설정 파일({@code
 * application.yml})을 실제로 읽어 프로필별 키가 맞게 들어가는지까지 본다.
 */
class SecurityProfileStartupTest {

  private static final String ACCESS_KEY =
      "app.keys.access-token=prod-like-access-token-signing-key-0001";
  private static final String REFRESH_KEY =
      "app.keys.refresh-token=prod-like-refresh-token-rotation-key-0001";

  /**
   * 설정 파일을 읽는다. 프로필은 {@code spring.profiles.active}로 준다. 앱처럼 {@code 15m} 같은 기간 값을 읽도록 Boot 변환기를
   * 넣는다.
   */
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withInitializer(context ->
              context.getBeanFactory().setConversionService(new ApplicationConversionService()))
          .withUserConfiguration(보안만.class);

  @Test
  void prod는_키가_없으면_시작하지_않는다() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("prod에는 실제 토큰 검증기")
              .hasMessageContaining("app.keys.access-token");
        });
  }

  @Test
  void prod에_local이_함께_켜져도_local_test_고정_키를_쓰지_않고_시작하지_않는다() {
    runner
        .withPropertyValues("spring.profiles.active=local,prod")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void prod는_키가_있으면_실제_검증기로_시작한다() {
    runner
        .withPropertyValues("spring.profiles.active=prod", ACCESS_KEY, REFRESH_KEY)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(AccessTokenVerifier.class);
          assertThat(context.getBean(AccessTokenVerifier.class)).isInstanceOf(AccessTokens.class);
        });
  }

  @Test
  void 키가_32바이트보다_짧으면_시작하지_않는다() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod", "app.keys.access-token=too-short", REFRESH_KEY)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32바이트 이상");
        });
  }

  @Test
  void 프로필과_키가_없으면_검증기_없이_시작한다() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(AccessTokenVerifier.class);
    });
  }

  @Test
  void local과_test는_고정_키의_실제_검증기로_시작한다() {
    for (String profile : new String[] {"local", "test"}) {
      runner
          .withPropertyValues("spring.profiles.active=" + profile)
          .run(context -> {
            assertThat(context).hasSingleBean(AccessTokens.class);
            assertThat(context.getEnvironment().getProperty("app.keys.access-token"))
                .isEqualTo(SecurityTestSupport.TEST_ACCESS_KEY);
          });
    }
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, AccessTokenConfig.class, ClockConfig.class})
  static class 보안만 {}
}
