package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.repository.SmsOptOutRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 수신 거부 조회는 판정 실패를 {@code false}로 바꾸지 않는다(#163 1-3, decisions 2026-11-26 "번호 HMAC"). */
class SmsOptOutsUnitTest {

  @Test
  void HMAC_계산이_실패하면_조회하지_않고_그_예외를_올린다() {
    AtomicBoolean queried = new AtomicBoolean();
    Predicate<String> repository =
        hmac -> {
          queried.set(true);
          return false;
        };
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> {
              throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            },
            lookup(repository));

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
            lookup(hmac -> false));

    assertThatThrownBy(() -> optOuts.isOptedOut("01012345678"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void DB_조회가_실패하면_false가_아니라_예외다() {
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> "a".repeat(64),
            lookup(hmac -> {
              throw new DataAccessResourceFailureException("db");
            }));

    assertThatThrownBy(() -> optOuts.isOptedOut("01012345678"))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void 조회는_계산한_HMAC_값으로_한다() {
    String hmac = "b".repeat(64);
    SmsOptOuts optOuts = new JpaSmsOptOuts(phone -> hmac, lookup(hmac::equals));

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

  // ---- 등록·해제(#370 A-1·A-3): 실패를 정상 결과로 바꾸지 않고, 지운 행이 있을 때만 감사를 남긴다 ----

  private static final Instant AT = Instant.parse("2026-11-28T03:00:00Z");

  @Test
  void 해제할_때_HMAC_계산이_실패하면_지우지_않고_그_예외를_올린다() {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    SmsOptOuts optOuts =
        new JpaSmsOptOuts(
            phone -> {
              throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            },
            repository);

    assertThatThrownBy(() -> optOuts.releaseForVerifiedOwner("bad", AT))
        .isInstanceOf(BusinessException.class);
    verify(repository, never()).deleteByPhoneHmac(anyString());
    verify(repository, never()).insertReleaseAudit(anyString(), any());
  }

  @Test
  void 지울_행이_없으면_false이고_감사를_넣지_않는다() {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    when(repository.deleteByPhoneHmac("c".repeat(64))).thenReturn(0);

    assertThat(
            new JpaSmsOptOuts(phone -> "c".repeat(64), repository)
                .releaseForVerifiedOwner("01012345678", AT))
        .isFalse();
    verify(repository, never()).insertReleaseAudit(anyString(), any());
  }

  @Test
  void 지운_뒤_감사를_넣지_못하면_true가_아니라_예외다() {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    when(repository.deleteByPhoneHmac("d".repeat(64))).thenReturn(1);
    when(repository.insertReleaseAudit("d".repeat(64), AT))
        .thenThrow(new DataAccessResourceFailureException("db"));

    assertThatThrownBy(() ->
            new JpaSmsOptOuts(phone -> "d".repeat(64), repository)
                .releaseForVerifiedOwner("01012345678", AT))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void 지운_수나_감사_수가_1이_아니면_예외로_되돌린다() {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    when(repository.deleteByPhoneHmac("e".repeat(64))).thenReturn(1);
    when(repository.insertReleaseAudit("e".repeat(64), AT)).thenReturn(0);

    assertThatThrownBy(() ->
            new JpaSmsOptOuts(phone -> "e".repeat(64), repository)
                .releaseForVerifiedOwner("01012345678", AT))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 등록은_넣은_행이_있을_때만_true다() {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    when(repository.insertIfAbsent("f".repeat(64), AT)).thenReturn(1, 0);
    SmsOptOuts optOuts = new JpaSmsOptOuts(phone -> "f".repeat(64), repository);

    assertThat(optOuts.optOut("01012345678", AT)).isTrue();
    assertThat(optOuts.optOut("01012345678", AT)).isFalse();
  }

  /** 조회만 쓰는 테스트의 저장소. */
  private static SmsOptOutRepository lookup(Predicate<String> exists) {
    SmsOptOutRepository repository = mock(SmsOptOutRepository.class);
    when(repository.existsById(anyString())).thenAnswer(call -> exists.test(call.getArgument(0)));
    return repository;
  }
}
