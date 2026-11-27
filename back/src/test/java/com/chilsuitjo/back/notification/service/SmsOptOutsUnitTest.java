package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.repository.SmsOptOutRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 수신 거부 조회는 판정 실패를 {@code false}로 바꾸지 않는다(#163 1-3, decisions 2026-11-26 "번호 HMAC"). */
class SmsOptOutsUnitTest {

  @Test
  void HMAC_계산이_실패하면_조회하지_않고_그_예외를_올린다() {
    AtomicBoolean queried = new AtomicBoolean();
    SmsOptOutRepository repository =
        hmac -> {
          queried.set(true);
          return false;
        };
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> {
              throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            },
            repository);

    assertThatThrownBy(() -> optOuts.isOptedOut("bad"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThat(queried).isFalse();
  }

  @Test
  void 계산_중_다른_실패도_그대로_올린다() {
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> {
              throw new IllegalStateException("hmac");
            },
            hmac -> false);

    assertThatThrownBy(() -> optOuts.isOptedOut("01012345678"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void DB_조회가_실패하면_false가_아니라_예외다() {
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> "a".repeat(64),
            hmac -> {
              throw new DataAccessResourceFailureException("db");
            });

    assertThatThrownBy(() -> optOuts.isOptedOut("01012345678"))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void 조회는_계산한_HMAC_값으로_한다() {
    String hmac = "b".repeat(64);
    SmsOptOuts optOuts = new JpaSmsOptOuts(phone -> hmac, hmac::equals);

    assertThat(optOuts.isOptedOut("01012345678")).isTrue();
  }

  @Test
  void 가짜도_형식만_다른_번호는_같게_보고_잘못된_번호는_VALIDATION_FAILED다() {
    FakeSmsOptOuts fake = new FakeSmsOptOuts().optOut("010-1234-5678");

    assertThat(fake.isOptedOut("01012345678")).isTrue();
    assertThat(fake.isOptedOut("010-1234-5679")).isFalse();
    assertThatThrownBy(() -> fake.isOptedOut("02-123-4567"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }
}
