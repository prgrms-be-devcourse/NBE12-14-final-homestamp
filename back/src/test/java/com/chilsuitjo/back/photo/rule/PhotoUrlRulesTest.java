package com.chilsuitjo.back.photo.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.photo.rule.PhotoUrlRules.UrlPlan;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 사진 주소의 서명 시각·만료·캐시(04 5-1 "보기", 06 0-1 {@code photo.url.*}, PHO-2a). */
class PhotoUrlRulesTest {

  private static final Duration MEMBER = Duration.ofHours(2);
  private static final Duration SHORT = Duration.ofMinutes(5);
  private static final Instant NOW = Instant.parse("2026-11-26T03:17:42.500Z");

  @Test
  void 회원_썸네일은_정각에_서명해_정각_더하기_2시간에_끝나고_1시간_캐시한다() {
    UrlPlan plan = PhotoUrlRules.forMember(PhotoVariant.THUMB, NOW, MEMBER, SHORT, null);

    assertThat(plan.signedAt()).isEqualTo(Instant.parse("2026-11-26T03:00:00Z"));
    assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-11-26T05:00:00Z"));
    assertThat(plan.cacheControl()).isEqualTo("private, max-age=3600");
  }

  @Test
  void 같은_시간_안에서는_썸네일_주소를_만들_값이_같다() {
    UrlPlan early =
        PhotoUrlRules.forMember(
            PhotoVariant.THUMB, Instant.parse("2026-11-26T03:00:00Z"), MEMBER, SHORT, null);
    UrlPlan late =
        PhotoUrlRules.forMember(
            PhotoVariant.THUMB, Instant.parse("2026-11-26T03:59:59.999Z"), MEMBER, SHORT, null);

    assertThat(late).isEqualTo(early);
  }

  @Test
  void 원본은_지금_서명해_5분에_끝나고_캐시_헤더가_없다() {
    UrlPlan plan = PhotoUrlRules.forMember(PhotoVariant.ORIGINAL, NOW, MEMBER, SHORT, null);

    assertThat(plan.signedAt()).isEqualTo(Instant.parse("2026-11-26T03:17:42Z"));
    assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-11-26T03:22:42Z"));
    assertThat(plan.cacheControl()).isNull();
  }

  @Test
  void PHO_2a_회원_썸네일_주소는_자격_증명_만료_빼기_10분을_넘지_않는다() {
    Instant credentials = NOW.plus(Duration.ofMinutes(25));

    UrlPlan plan = PhotoUrlRules.forMember(PhotoVariant.THUMB, NOW, MEMBER, SHORT, credentials);

    assertThat(plan.expiresAt()).isBeforeOrEqualTo(credentials.minus(Duration.ofMinutes(10)));
    assertThat(plan.expiresAt()).isAfter(NOW);
    // 캐시는 주소의 남은 시간보다 길지 않다(04 5-1)
    long remaining = Duration.between(NOW, plan.expiresAt()).getSeconds();
    assertThat(plan.cacheControl()).isEqualTo("private, max-age=" + remaining);
  }

  @Test
  void 원본_주소도_자격_증명_만료_빼기_10분을_넘지_않는다() {
    Instant credentials = NOW.plus(Duration.ofMinutes(13));

    UrlPlan plan = PhotoUrlRules.forMember(PhotoVariant.ORIGINAL, NOW, MEMBER, SHORT, credentials);

    assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-11-26T03:20:42Z"));
  }

  @Test
  void 자격_증명이_10분_안에_끝나면_주소를_만들지_않는다() {
    Instant credentials = NOW.plus(Duration.ofMinutes(10));

    assertThatThrownBy(
            () -> PhotoUrlRules.forMember(PhotoVariant.THUMB, NOW, MEMBER, SHORT, credentials))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- 링크 열람자 ----

  @Test
  void 링크_열람자_주소는_정각이_아니라_지금_서명해_5분에_끝나고_저장하지_않게_한다() {
    UrlPlan plan = PhotoUrlRules.forLink(NOW, SHORT, null);

    assertThat(plan.signedAt()).isEqualTo(Instant.parse("2026-11-26T03:17:42Z"));
    assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-11-26T03:22:42Z"));
    assertThat(plan.cacheControl()).isEqualTo("no-store");
  }

  @Test
  void 링크_열람자_주소는_요청마다_달라진다() {
    UrlPlan first = PhotoUrlRules.forLink(NOW, SHORT, null);
    UrlPlan later = PhotoUrlRules.forLink(NOW.plusSeconds(1), SHORT, null);

    assertThat(later.signedAt()).isAfter(first.signedAt());
    assertThat(later.expiresAt()).isAfter(first.expiresAt());
  }

  @Test
  void 링크_열람자_주소도_자격_증명_만료_빼기_10분을_넘지_않는다() {
    Instant credentials = NOW.plus(Duration.ofMinutes(13));

    UrlPlan plan = PhotoUrlRules.forLink(NOW, SHORT, credentials);

    assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-11-26T03:20:42Z"));
    assertThat(plan.cacheControl()).isEqualTo("no-store");
  }

  @Test
  void 자격_증명이_10분_안에_끝나면_링크_열람자_주소도_만들지_않는다() {
    Instant credentials = NOW.plus(Duration.ofMinutes(10));

    assertThatThrownBy(() -> PhotoUrlRules.forLink(NOW, SHORT, credentials))
        .isInstanceOf(IllegalStateException.class);
  }
}
