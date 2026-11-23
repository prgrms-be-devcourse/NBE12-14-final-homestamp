package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 설정된 전달 결과 조회 간격을 발송과 조회 작업자가 함께 쓰게 한다. */
@Configuration(proxyBeanMethods = false)
class DeliveryPollScheduleConfig {

  @Bean
  DeliveryPollSchedule deliveryPollSchedule(
      @Value("${app.delivery.poll:1m,5m,15m}") List<Duration> configured) {
    return new DeliveryPollSchedule(configured);
  }
}
