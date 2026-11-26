package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.ClockConfig;
import com.chilsuitjo.back.global.config.ssm.ResetSsmTestParameterSource;
import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmParameterSource;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 프로필마다 토큰 검증기가 켜지는지, {@code prod}가 키 없이 시작을 거부하는지 본다. DB 없이 보안 설정만 띄우고, 설정 파일({@code
 * application.yml})을 실제로 읽어 프로필별 키가 맞게 들어가는지까지 본다.
 */
@ExtendWith(ResetSsmTestParameterSource.class)
class SecurityProfileStartupTest {

  /**
   * 설정 파일을 읽는다. 프로필은 {@code spring.profiles.active}로 준다. 앱처럼 {@code 15m} 같은 기간 값을 읽도록 Boot 변환기를
   * 넣는다.
   */
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withInitializer(new SsmAwareConfigDataInitializer())
          .withInitializer(context ->
              context.getBeanFactory().setConversionService(new ApplicationConversionService()))
          .withUserConfiguration(보안만.class);

  @Test
  void prod는_SSM_필수_설정이_없으면_시작하지_않는다() {
    SsmTestParameterSource.setParameters(List.of());
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("필수 SSM 앱 설정이 없다");
        });
  }

  @Test
  void prod에_local이_함께_켜져도_local_test_고정_키를_쓰지_않고_시작하지_않는다() {
    SsmTestParameterSource.setParameters(List.of());
    runner
        .withPropertyValues("spring.profiles.active=local,prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("필수 SSM 앱 설정이 없다");
        });
  }

  @Test
  void prod는_키가_있으면_실제_검증기로_시작한다() {
    SsmTestParameterSource.setParameters(SsmTestParameterSource.validParameters());
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(AccessTokenVerifier.class);
          assertThat(context.getBean(AccessTokenVerifier.class)).isInstanceOf(AccessTokens.class);
        });
  }

  @Test
  void 키가_32바이트보다_짧으면_시작하지_않는다() {
    List<SsmConfigParameter> parameters = new ArrayList<>(SsmTestParameterSource.validParameters());
    parameters.replaceAll(parameter ->
        parameter.name().endsWith("app.keys.access-token")
            ? new SsmConfigParameter(parameter.name(), "too-short", "SecureString")
            : parameter);
    SsmTestParameterSource.setParameters(parameters);
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("32바이트 이상");
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
            assertThat(SsmTestParameterSource.calls()).isZero();
          });
    }
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, AccessTokenConfig.class, ClockConfig.class})
  static class 보안만 {}

  private static class SsmAwareConfigDataInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var environment = context.getEnvironment();
      RandomValuePropertySource.addToEnvironment(environment);
      DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();
      bootstrapContext.register(SsmParameterSource.class, ignored -> new SsmTestParameterSource());
      ConfigDataEnvironmentPostProcessor.applyTo(environment, context, bootstrapContext);
      bootstrapContext.close(context);
      DefaultPropertiesPropertySource.moveToEnd(environment);
    }
  }
}
