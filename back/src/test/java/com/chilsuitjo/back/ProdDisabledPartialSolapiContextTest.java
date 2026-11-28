package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import com.chilsuitjo.back.notification.repository.DisabledSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.repository.SolapiClient;
import com.chilsuitjo.back.notification.repository.SolapiSettings;
import com.chilsuitjo.back.notification.repository.SolapiSmsGateway;
import com.chilsuitjo.back.notification.service.DisabledVerificationSmsSender;
import com.chilsuitjo.back.notification.service.SolapiVerificationSmsSender;
import com.chilsuitjo.back.notification.service.VerificationSmsSender;
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
 * 운영 기본(prod + yml {@code app.sms.mode=disabled})에서 SSM에 솔라피 키가 일부만 미리 들어 있어도 정상 기동하고, 솔라피 부품은 하나도
 * 만들지 않는다(업체를 부를 길이 없음). 키를 먼저 넣고 나중에 모드를 바꾸는 순서를 막지 않는다.
 */
@Import(ProdTestSchema.MigratedDatabase.class)
@SpringBootTest(properties = {"app.outbox.worker.enabled=false"})
@ActiveProfiles("prod")
class ProdDisabledPartialSolapiContextTest {

  @BeforeAll
  static void provideFakeSsmSettings() {
    List<SsmConfigParameter> parameters = new ArrayList<>(SsmTestParameterSource.validParameters());
    parameters.add(
        new SsmConfigParameter(
            "/homestamp/prod/app/" + "app.sms.solapi.api-key", "test-key", "SecureString"));
    SsmTestParameterSource.setParameters(parameters);
  }

  @AfterAll
  static void resetFakeSsmSettings() {
    SsmTestParameterSource.reset();
  }

  @Autowired private ApplicationContext context;

  @Test
  void 꺼진_문자_방식은_그대로이고_솔라피_부품은_만들지_않는다() {
    assertThat(context.getBean(SmsGateway.class)).isInstanceOf(DisabledSmsGateway.class);
    assertThat(context.getBean(VerificationSmsSender.class))
        .isInstanceOf(DisabledVerificationSmsSender.class);
    assertThat(context.getBeanNamesForType(SolapiClient.class)).isEmpty();
    assertThat(context.getBeanNamesForType(SolapiSettings.class)).isEmpty();
    assertThat(context.getBeanNamesForType(SolapiSmsGateway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(SolapiVerificationSmsSender.class)).isEmpty();
  }
}
