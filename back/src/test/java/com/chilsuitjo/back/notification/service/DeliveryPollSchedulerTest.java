package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 전달 결과 작업자의 예약 실행 부품(#120 3-B2). 실제 시각을 기다리지 않도록 {@code poll()}을 직접 부르고, 켜고 끄기는 DB 없는 작은 컨텍스트로
 * 본다(예약 실행이 켜진 컨텍스트가 테스트 캐시에 남아 다른 테스트의 통지를 가져가지 않게).
 */
class DeliveryPollSchedulerTest {

  private final DeliveryResultPoller poller = mock(DeliveryResultPoller.class);

  @Test
  void 한_묶음이_꽉_차면_밀린_것이_없어질_때까지_이어서_돈다() {
    when(poller.runOnce(2)).thenReturn(2, 2, 1);

    new DeliveryPollScheduler(poller, 2).poll();

    verify(poller, times(3)).runOnce(2);
  }

  @Test
  void 가져갈_통지가_없으면_한_번만_묻고_끝난다() {
    when(poller.runOnce(500)).thenReturn(0);

    new DeliveryPollScheduler(poller, 500).poll();

    verify(poller, times(1)).runOnce(500);
  }

  @Test
  void 실행이_실패해도_예외를_밖으로_던지지_않고_다음_실행은_다시_돈다() {
    when(poller.runOnce(2)).thenThrow(new IllegalStateException("DB 끊김")).thenReturn(1);
    DeliveryPollScheduler scheduler = new DeliveryPollScheduler(poller, 2);

    scheduler.poll();
    scheduler.poll();

    verify(poller, times(2)).runOnce(2);
  }

  @Test
  void 묶음_크기가_1보다_작으면_시작하지_않는다() {
    assertThatThrownBy(() -> new DeliveryPollScheduler(poller, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(DeliveryResultPoller.class, () -> poller)
          .withUserConfiguration(DeliveryPollScheduler.class, DeliveryPollerOffWarning.class);

  @Test
  void 켜면_예약_실행_부품이_생기고_꺼짐_경고는_없다() {
    runner
        .withPropertyValues("app.delivery.poller.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(DeliveryPollScheduler.class);
          assertThat(context).doesNotHaveBean(DeliveryPollerOffWarning.class);
        });
  }

  @Test
  void 끄거나_설정이_없으면_예약_실행_부품이_없고_꺼짐_경고가_생긴다() {
    runner
        .withPropertyValues("app.delivery.poller.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(DeliveryPollScheduler.class);
          assertThat(context).hasSingleBean(DeliveryPollerOffWarning.class);
        });
    runner.run(context -> {
      assertThat(context).doesNotHaveBean(DeliveryPollScheduler.class);
      assertThat(context).hasSingleBean(DeliveryPollerOffWarning.class);
    });
  }

  @Test
  void 기본_설정_파일과_테스트_설정은_작업자를_끈다() {
    // 서비스를 열 때만 배포 설정으로 켠다. 기본 파일에서 켜진 채로 가짜 발송기와 함께 돌지 않게 값을 본다
    assertThat(yaml("application.yml").getProperty("app.delivery.poller.enabled"))
        .isEqualTo("false");
    assertThat(yaml("config/application.yml").getProperty("app.delivery.poller.enabled"))
        .isEqualTo("false");
  }

  @Test
  void 작업자를_꺼도_유효한_다른_조회_일정으로_서비스가_시작한다() {
    ApplicationContextRunner configurationRunner =
        new ApplicationContextRunner()
            .withInitializer(context ->
                context
                    .getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(DeliveryPollClaimer.class, () -> mock(DeliveryPollClaimer.class))
            .withBean(NoticeRepository.class, () -> mock(NoticeRepository.class))
            .withBean(SmsGateway.class, () -> mock(SmsGateway.class))
            .withBean(Clock.class, () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            .withBean(
                PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(
                DeliveryPollScheduleConfig.class,
                DeliveryResultPoller.class,
                DeliveryPollScheduler.class);

    configurationRunner
        .withPropertyValues("app.delivery.poll=2m,7m,10m", "app.delivery.poller.enabled=false")
        .run(context -> {
          assertThat(context).hasSingleBean(DeliveryResultPoller.class);
          assertThat(context).doesNotHaveBean(DeliveryPollScheduler.class);
          DeliveryPollSchedule schedule = context.getBean(DeliveryPollSchedule.class);
          assertThat(schedule.first(Instant.EPOCH, Duration.ofDays(3)))
              .isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(2)));
          assertThat(
                  schedule.next(
                      Instant.EPOCH, Instant.EPOCH.plus(Duration.ofMinutes(2)), Duration.ofDays(3)))
              .contains(Instant.EPOCH.plus(Duration.ofMinutes(7)));
        });

    configurationRunner
        .withPropertyValues("app.delivery.poll=2m,0m,10m", "app.delivery.poller.enabled=false")
        .run(context -> assertThat(context).hasFailed());
  }

  private static Properties yaml(String path) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(path));
    return factory.getObject();
  }
}
