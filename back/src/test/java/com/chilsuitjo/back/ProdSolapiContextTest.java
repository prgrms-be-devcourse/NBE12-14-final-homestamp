package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import com.chilsuitjo.back.notification.repository.DisabledSmsGateway;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.repository.SolapiClient;
import com.chilsuitjo.back.notification.repository.SolapiSettings;
import com.chilsuitjo.back.notification.repository.SolapiSmsGateway;
import com.chilsuitjo.back.notification.service.SolapiVerificationSmsSender;
import com.chilsuitjo.back.notification.service.VerificationSmsSender;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 운영 기동 경로 그대로(가짜 SSM → {@code SsmAppParameterMapper} → {@code SmsModeGuard} → 빈) {@code
 * app.sms.mode=solapi}이고 SSM에 솔라피 SecureString 3개가 있으면 실제 솔라피 구현이 만들어지는지 본다. 주소는 yml prod 값(공식
 * 주소)이다. 보내기·조회는 부르지 않는다: 아웃박스 작업자와 전달 결과 조회기를 끄고, 문자를 만드는 요청을 보내지 않는다. 실제 비밀값은 쓰지 않는다.
 */
@Import(ProdTestSchema.MigratedDatabase.class)
@SpringBootTest(
    properties = {
      "app.outbox.worker.enabled=false",
      "app.delivery.poller.enabled=false",
      "app.sms.mode=solapi"
    })
@ActiveProfiles("prod")
class ProdSolapiContextTest {

  @BeforeAll
  static void provideFakeSsmSettings() {
    List<SsmConfigParameter> parameters = new ArrayList<>(SsmTestParameterSource.validParameters());
    String prefix = "/homestamp/prod/app/";
    parameters.add(
        new SsmConfigParameter(prefix + "app.sms.solapi.api-key", "test-key", "SecureString"));
    parameters.add(
        new SsmConfigParameter(
            prefix + "app.sms.solapi.api-secret", "test-secret", "SecureString"));
    parameters.add(
        new SsmConfigParameter(prefix + "app.sms.solapi.sender", "0212345678", "SecureString"));
    SsmTestParameterSource.setParameters(parameters);
  }

  @AfterAll
  static void resetFakeSsmSettings() {
    SsmTestParameterSource.reset();
  }

  @Autowired private ApplicationContext context;

  @Test
  void SSM의_솔라피_키로_실제_솔라피_구현이_만들어지고_가짜와_꺼짐은_없다() {
    assertThat(context.getBean(SmsGateway.class)).isInstanceOf(SolapiSmsGateway.class);
    assertThat(context.getBean(VerificationSmsSender.class))
        .isInstanceOf(SolapiVerificationSmsSender.class);
    assertThat(context.getBeanNamesForType(SolapiClient.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(DisabledSmsGateway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FakeSmsGateway.class)).isEmpty();
  }

  @Test
  void 설정은_SSM_값과_yml_공식_주소이고_문자열로_보이지_않는다() {
    SolapiSettings settings = context.getBean(SolapiSettings.class);

    assertThat(settings.baseUrl()).isEqualTo(URI.create("https://api.solapi.com"));
    assertThat(settings.apiKey()).isEqualTo("test-key");
    assertThat(settings.sender()).isEqualTo("0212345678");
    assertThat(settings.toString())
        .doesNotContain("test-key")
        .doesNotContain("test-secret")
        .doesNotContain("0212345678");
  }
}
