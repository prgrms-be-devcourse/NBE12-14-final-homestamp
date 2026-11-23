package com.chilsuitjo.back.global.scheduler;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예약 작업(아웃박스 작업자, 기다림 타이머, 켜질 때 따라잡기)을 켠다. 작업 자체는 각 패키지가 {@code @Scheduled}로 둔다(04 "켜질 때 + 일정 간격").
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
