package com.chilsuitjo.back.global.config.ssm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Migration 설정은 SSM·Spring ConfigData로 공급하지 않는다(파일 → {@code MigrationMode} 직접 구성).
 *
 * <p>앱 쪽 Spring 설정에서 {@code homestamp-ssm:migration}을 import해도 SSM을 부르지 않고 시작 단계에서 거절되는지, 앱 경로는
 * 그대로인지 본다. DB·AWS에 연결하지 않는다.
 */
class MigrationSsmConfigDataIntegrationTest {

  @Configuration(proxyBeanMethods = false)
  static class Empty {}

  /** 호출 횟수를 세는 SSM 출처. */
  static final class CountingSsm implements SsmParameterSource {
    int calls;

    @Override
    public List<SsmConfigParameter> loadAppParameters() {
      calls++;
      return SsmTestParameterSource.validParameters();
    }
  }

  static ConfigurableApplicationContext run(SsmParameterSource ssm, String... args) {
    SpringApplication application = new SpringApplication(Empty.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.addBootstrapRegistryInitializer(
        registry -> registry.register(SsmParameterSource.class, ignored -> ssm));
    return application.run(args);
  }

  @Test
  void migration_경로_import는_SSM을_부르지_않고_거절된다() {
    CountingSsm ssm = new CountingSsm();
    assertThatThrownBy(() ->
            run(
                ssm,
                "--spring.config.location=optional:classpath:/migration-none.properties",
                "--spring.config.import=homestamp-ssm:migration"))
        .hasStackTraceContaining("SSM ConfigData 위치에는 추가 경로를 지정할 수 없다");
    assertThat(ssm.calls).isZero();
  }

  @Test
  void optional_접두어나_on_not_found_ignore로도_migration_경로는_열리지_않는다() {
    CountingSsm ssm = new CountingSsm();
    assertThatThrownBy(() ->
            run(
                ssm,
                "--spring.config.location=optional:classpath:/migration-none.properties",
                "--spring.config.on-not-found=ignore",
                "--spring.config.import=optional:homestamp-ssm:migration"))
        .hasStackTraceContaining("SSM ConfigData 위치에는 추가 경로를 지정할 수 없다");
    assertThat(ssm.calls).isZero();
  }

  @Test
  void 앱_경로는_그대로_SSM_앱_설정만_읽는다() {
    CountingSsm ssm = new CountingSsm();
    try (ConfigurableApplicationContext context =
        run(
            ssm,
            "--spring.config.location=optional:classpath:/migration-none.properties",
            "--spring.config.import=homestamp-ssm:")) {
      assertThat(ssm.calls).isEqualTo(1);
      assertThat(context.getEnvironment().getProperty("app.migration.owner-role")).isNull();
      assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNotBlank();
    }
  }
}
