package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.service.FakeRecipientDirectory;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 프로필이 없으면(운영과 같음) API 문서를 만들지 않는다(docs/rules/04 API 문서: 내부 API 구조 노출 방지). */
@Import({TestcontainersConfiguration.class, ApiDocsOffByDefaultTest.TestPorts.class})
@SpringBootTest
class ApiDocsOffByDefaultTest {

  @Autowired private ApplicationContext context;

  @Test
  void 프로필이_없으면_API_문서가_꺼져_있다() {
    assertThat(context.getBeanNamesForType(OpenApiWebMvcResource.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FakeSmsGateway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FakeRecipientDirectory.class)).isEmpty();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestPorts {

    @Bean
    SmsGateway smsGateway() {
      return message -> SmsSendResult.retry();
    }

    @Bean
    RecipientDirectory recipientDirectory() {
      return new RecipientDirectory() {
        @Override
        public Optional<String> phoneOfParty(UUID partyId) {
          return Optional.empty();
        }

        @Override
        public Optional<String> phoneOfMember(UUID memberId) {
          return Optional.empty();
        }

        @Override
        public Optional<String> phoneOfRecipient(UUID partyId) {
          return Optional.empty();
        }
      };
    }
  }
}
