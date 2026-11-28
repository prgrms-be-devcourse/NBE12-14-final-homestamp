package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.config.ssm.ResetSsmTestParameterSource;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 일반 앱은 잘못된 프로필·문자 방식이면 빈을 만들기 전에 멈춘다(ProfileGuard·SmsModeGuard, decisions 2026-11-25 운영 문자 모드).
 * 검사가 빠지면 닿을 수 없는 DB 주소에서 다른 오류로 실패하므로 문구로 구분한다. 설정은 명령줄 인자로 넘긴다(yml보다 우선해야 "값 없음"을 만들 수 있다). prod는
 * 운영처럼 가짜 SSM에서 설정을 받는다. SSM이 정하는 DB 주소를 명령줄로 덮으면 SsmProdSettingsGuard가 먼저 멈추므로, prod에는 닿을 수 없는 DB
 * 주소를 넘기지 않는다(가짜 SSM 주소 db.example도 닿지 않는다).
 */
@ExtendWith(ResetSsmTestParameterSource.class)
class StartupGuardTest {

  @BeforeEach
  void provideFakeSsmSettings() {
    SsmTestParameterSource.setParameters(SsmTestParameterSource.validParameters());
  }

  private static final String NO_DB = "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none";

  private static void start(String... args) {
    boolean prod =
        Arrays.stream(args)
            .anyMatch(arg -> arg.startsWith("--spring.profiles.active=") && arg.contains("prod"));
    List<String> all = new ArrayList<>();
    if (!prod) {
      all.add(NO_DB);
    }
    all.add("--spring.main.banner-mode=off");
    all.add("--app.outbox.worker.enabled=false");
    all.addAll(List.of(args));
    try (ConfigurableApplicationContext ignored =
        new SpringApplicationBuilder(BackApplication.class)
            .web(WebApplicationType.NONE)
            .run(all.toArray(String[]::new))) {
      throw new AssertionError("기동하면 안 되는 설정으로 기동했다");
    }
  }

  @Test
  void 프로필이_없으면_일반_앱은_시작하지_않는다() {
    assertThatThrownBy(() -> start()).hasStackTraceContaining("활성 프로필(local·test·prod)이 없어");
  }

  @Test
  void prod와_local을_함께_켜면_시작하지_않는다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=prod,local"))
        .hasStackTraceContaining("prod는 local·test와 함께 켜지 않는다");
  }

  @Test
  void prod와_test를_함께_켜면_시작하지_않는다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=test,prod"))
        .hasStackTraceContaining("prod는 local·test와 함께 켜지 않는다");
  }

  @Test
  void prod에서_가짜_문자면_시작하지_않는다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=prod", "--app.sms.mode=fake"))
        .hasStackTraceContaining("prod에서는 가짜 문자(app.sms.mode=fake)로 시작하지 않는다");
  }

  @Test
  void prod에서_문자_방식이_없으면_시작하지_않는다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=prod", "--app.sms.mode="))
        .hasStackTraceContaining("문자 방식(app.sms.mode)이 없어 시작하지 않는다");
  }

  @Test
  void prod에서_솔라피_키가_없으면_시작하지_않는다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=prod", "--app.sms.mode=solapi"))
        .hasStackTraceContaining("솔라피 설정이 없어 시작하지 않는다: app.sms.solapi.api-key");
  }

  @Test
  void prod에서_솔라피_주소가_공식_주소가_아니면_시작하지_않는다() {
    assertThatThrownBy(() ->
            start(
                "--spring.profiles.active=prod",
                "--app.sms.mode=solapi",
                "--app.sms.solapi.api-key=k",
                "--app.sms.solapi.api-secret=s",
                "--app.sms.solapi.sender=01000000000",
                "--app.sms.solapi.base-url=https://example.com"))
        .hasStackTraceContaining("prod 솔라피 주소는 공식 주소만 받는다");
  }

  @Test
  void 모르는_값이나_대문자는_다른_방식으로_바꾸지_않고_멈춘다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=prod", "--app.sms.mode=DISABLED"))
        .hasStackTraceContaining("fake·disabled·solapi 중 하나다");
  }

  @Test
  void test에서도_문자_방식이_비면_가짜로_대신하지_않고_멈춘다() {
    assertThatThrownBy(() -> start("--spring.profiles.active=test", "--app.sms.mode="))
        .hasStackTraceContaining("문자 방식(app.sms.mode)이 없어 시작하지 않는다");
  }
}
