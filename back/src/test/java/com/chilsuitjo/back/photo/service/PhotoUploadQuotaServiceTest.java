package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PhotoUploadQuotaServiceTest.FixedClock.class})
class PhotoUploadQuotaServiceTest {

  // 한국 시간 2026-11-26 00:00:01. UTC 날짜로 세면 하루 전 행이 생긴다.
  private static final Instant NOW = Instant.parse("2026-11-25T15:00:01Z");
  private static final LocalDate TODAY_IN_SEOUL = LocalDate.of(2026, 11, 26);

  @Autowired private PhotoUploadQuotaService quota;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void countsByMemberAndSeoulDayAndReportsTimeUntilMidnight() {
    UUID member = UUID.randomUUID();
    UUID otherMember = UUID.randomUUID();
    reserve(member, 97);
    reserve(member, 3);
    reserve(otherMember, 10);

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> reserve(member, 1))
        .satisfies(error -> {
          assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_LIMIT);
          assertThat(error.retryAfter()).contains(Duration.ofDays(1).minusSeconds(1));
        });
    assertThat(count(member)).isEqualTo(100);
    assertThat(count(otherMember)).isEqualTo(10);
  }

  @Test
  void doesNotInsertARowForARequestOverTheLimit() {
    UUID member = UUID.randomUUID();

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> reserve(member, 101))
        .satisfies(error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_LIMIT));

    assertThat(count(member)).isZero();
  }

  @Test
  void concurrentRequestsCannotOverspendTheRemainingQuota() throws Exception {
    UUID member = UUID.randomUUID();
    reserve(member, 97);
    var start = new CountDownLatch(1);
    Callable<Boolean> upload =
        () -> {
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 발급 테스트를 시작하지 못했다.");
          }
          try {
            reserve(member, 2);
            return true;
          } catch (BusinessException e) {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.UPLOAD_LIMIT);
            return false;
          }
        };

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(upload);
      var second = pool.submit(upload);
      start.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
    }
    assertThat(count(member)).isEqualTo(99);
  }

  @Test
  void rollsBackTheQuotaWhenTheIssuingTransactionRollsBack() {
    UUID member = UUID.randomUUID();
    reserve(member, 96);

    transaction.executeWithoutResult(status -> {
      quota.reserve(member, 4);
      status.setRollbackOnly();
    });

    assertThat(count(member)).isEqualTo(96);
  }

  private void reserve(UUID member, int count) {
    transaction.executeWithoutResult(status -> quota.reserve(member, count));
  }

  private Integer count(UUID member) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(count), 0) FROM state.rate_counter
        WHERE counter_key = ? AND window_date = ?
        """,
        Integer.class,
        "upload:" + member,
        TODAY_IN_SEOUL);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {

    @Bean
    @Primary
    Clock uploadClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
