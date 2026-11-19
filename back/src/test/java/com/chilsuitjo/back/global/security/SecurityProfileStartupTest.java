package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 프로필마다 어떤 토큰 검증기로 뜨는지, {@code prod}가 실제 검증기 없이 시작을 거부하는지 본다. DB 없이 보안 설정만 띄운다. */
class SecurityProfileStartupTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner().withUserConfiguration(보안만.class);

  @Test
  void prod는_실제_검증기가_없으면_시작하지_않는다() {
    runner
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("prod에는 실제 토큰 검증기");
        });
  }

  @Test
  void prod에_local이_함께_켜져도_가짜_검증기를_쓰지_않고_시작하지_않는다() {
    runner
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("local", "prod"))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void prod는_실제_검증기가_있으면_시작한다() {
    runner
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
        .withBean(AccessTokenVerifier.class, () -> token -> Optional.<UUID>empty())
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(FakeAccessTokenVerifier.class);
        });
  }

  @Test
  void 프로필이_없으면_검증기_없이_시작한다() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(AccessTokenVerifier.class);
    });
  }

  @Test
  void local과_test는_가짜_검증기로_시작한다() {
    for (String profile : new String[] {"local", "test"}) {
      runner
          .withInitializer(c -> c.getEnvironment().setActiveProfiles(profile))
          .run(context -> assertThat(context).hasSingleBean(FakeAccessTokenVerifier.class));
    }
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, FakeAccessTokenVerifier.class})
  static class 보안만 {}
}
