package com.chilsuitjo.back.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.service.FakeRecipientDirectory;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class NotificationFakeProfileTest {

  private final ApplicationContextRunner fakeComponents =
      new ApplicationContextRunner().withUserConfiguration(FakeComponents.class);

  @Test
  void local_프로필에서는_Fake_SMS_구현을_등록한다() {
    assertFakeComponentsRegistered("local");
  }

  @Test
  void test_프로필에서는_Fake_SMS_구현을_등록한다() {
    assertFakeComponentsRegistered("test");
  }

  @Test
  void prod_프로필에서는_Fake_SMS_구현을_등록하지_않는다() {
    assertFakeComponentsNotRegistered("prod");
  }

  @Test
  void prod와_local이_함께_활성화되어도_Fake_SMS_구현을_등록하지_않는다() {
    assertFakeComponentsNotRegistered("local,prod");
  }

  @Test
  void prod와_test가_함께_활성화되어도_Fake_SMS_구현을_등록하지_않는다() {
    fakeComponents
        .withPropertyValues("spring.profiles.active=test,prod")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(FakeSmsGateway.class);
          assertThat(context).doesNotHaveBean(FakeRecipientDirectory.class);
        });
  }

  @Test
  void 활성_프로필이_없으면_Fake_SMS_구현을_등록하지_않는다() {
    fakeComponents.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(FakeSmsGateway.class);
      assertThat(context).doesNotHaveBean(FakeRecipientDirectory.class);
    });
  }

  @Test
  void prod에서_Fake_외의_발송_구현이_없으면_OutboxWorker를_만들지_못한다() {
    workerContext("prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
        });
  }

  private void assertFakeComponentsRegistered(String profile) {
    fakeComponents
        .withPropertyValues("spring.profiles.active=" + profile)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FakeSmsGateway.class);
          assertThat(context).hasSingleBean(FakeRecipientDirectory.class);
          assertThat(context).hasSingleBean(SmsGateway.class);
          assertThat(context).hasSingleBean(RecipientDirectory.class);
        });
  }

  private void assertFakeComponentsNotRegistered(String profiles) {
    fakeComponents
        .withPropertyValues("spring.profiles.active=" + profiles)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(FakeSmsGateway.class);
          assertThat(context).doesNotHaveBean(FakeRecipientDirectory.class);
          assertThat(context).doesNotHaveBean(SmsGateway.class);
          assertThat(context).doesNotHaveBean(RecipientDirectory.class);
        });
  }

  private ApplicationContextRunner workerContext(String profiles) {
    return fakeComponents
        .withUserConfiguration(OutboxWorker.class)
        .withBean(OutboxRepository.class, () -> repositoryStub(OutboxRepository.class))
        .withBean(NoticeRepository.class, () -> repositoryStub(NoticeRepository.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(PlatformTransactionManager.class, NotificationFakeProfileTest::transactionManager)
        .withPropertyValues("spring.profiles.active=" + profiles);
  }

  private static <T> T repositoryStub(Class<T> repositoryType) {
    return repositoryType.cast(
        Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (proxy, method, arguments) -> {
              if (method.getReturnType() == boolean.class) {
                return false;
              }
              if (method.getReturnType() == long.class) {
                return 0L;
              }
              if (method.getReturnType() == int.class) {
                return 0;
              }
              if (method.getReturnType() == UUID.class) {
                return UUID.randomUUID();
              }
              return null;
            }));
  }

  private static PlatformTransactionManager transactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  @Configuration(proxyBeanMethods = false)
  @Import({FakeSmsGateway.class, FakeRecipientDirectory.class})
  static class FakeComponents {}
}
