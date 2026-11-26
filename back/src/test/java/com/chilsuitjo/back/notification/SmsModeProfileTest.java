package com.chilsuitjo.back.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.service.DisabledRecipientDirectory;
import com.chilsuitjo.back.notification.service.FakeRecipientDirectory;
import com.chilsuitjo.back.notification.service.FakeVerificationSmsSender;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import com.chilsuitjo.back.notification.service.VerificationSmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** local·test는 가짜 문자(app.sms.mode=fake)로 뜬다(decisions 2026-11-25 운영 문자 모드). 실제 문자는 보내지 않는다. */
abstract class SmsModeProfileTest {

  @Autowired private ApplicationContext context;

  @Test
  void 문자_부품은_모두_가짜다() {
    assertThat(context.getBean(SmsGateway.class)).isInstanceOf(FakeSmsGateway.class);
    assertThat(context.getBean(VerificationSmsSender.class))
        .isInstanceOf(FakeVerificationSmsSender.class);
    assertThat(context.getBean(RecipientDirectory.class))
        .isInstanceOf(FakeRecipientDirectory.class);
    assertThat(context.getBeanNamesForType(DisabledRecipientDirectory.class)).isEmpty();
  }

  @Import(TestcontainersConfiguration.class)
  @SpringBootTest
  @ActiveProfiles("local")
  static class Local extends SmsModeProfileTest {}

  @Import(TestcontainersConfiguration.class)
  @SpringBootTest
  @ActiveProfiles("test")
  static class TestProfile extends SmsModeProfileTest {}
}
