package com.chilsuitjo.back.photo.rule;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 사진 주소({@code POST /api/photos/view-urls})의 서명 시각·만료·캐시(04 5-1 "보기", 06 0-1 {@code photo.url.*}).
 * 순수 함수라 Clock·S3를 부르지 않는다.
 *
 * <ul>
 *   <li>회원 썸네일: 정각에 서명해 정각 + {@code photo.url.member}까지. 같은 시간 안에서는 같은 주소라 브라우저가 1시간 캐시한다
 *   <li>원본: 지금 서명해 {@code photo.url.short}까지
 *   <li>링크 열람자({@code PhotoService.linkPhotos}): 썸네일·원본 모두 지금 서명해 {@code photo.url.short}까지, {@code
 *       no-store}
 *   <li>어느 쪽이든 만료는 서명한 자격 증명 만료 − 10분을 넘지 않는다(04 5-1 "주소 수명은 서명한 자격 증명을 넘지 않는다", 06 PHO-2a)
 * </ul>
 */
public final class PhotoUrlRules {

  /** 한 번에 받는 사진 수(06 0-1 {@code photo.url.batch.max}). 내려받기 묶음(DL-1)과 같은 50장이다. */
  public static final int MAX_BATCH = 50;

  /** 주소 만료와 자격 증명 만료 사이에 남기는 여유. */
  static final Duration CREDENTIAL_MARGIN = Duration.ofMinutes(10);

  /** 회원 썸네일을 브라우저가 다시 받지 않는 시간(03 4장 "1시간 캐시"). 주소의 남은 시간보다 길지 않게 줄인다. */
  static final Duration MEMBER_THUMB_CACHE = Duration.ofHours(1);

  /** 링크 열람자에게 주는 캐시. 브라우저·중간 캐시 어디에도 남기지 않는다(04 5-1). */
  static final String LINK_CACHE = "no-store";

  private PhotoUrlRules() {}

  /**
   * 주소 하나를 만들 값.
   *
   * @param signedAt 서명 시각({@code X-Amz-Date}). 초 단위다
   * @param expiresAt 주소가 끝나는 시각. 초 단위이고 {@code signedAt}보다 뒤다
   * @param cacheControl 사진을 받을 때 S3가 돌려줄 {@code Cache-Control}. 없으면 null
   */
  public record UrlPlan(Instant signedAt, Instant expiresAt, String cacheControl) {}

  /**
   * 회원이 받을 주소의 서명 시각·만료·캐시.
   *
   * @param now 지금 서버 시각
   * @param memberTtl {@code photo.url.member}(정각부터)
   * @param shortTtl {@code photo.url.short}
   * @param credentialsExpireAt 서명에 쓸 자격 증명의 만료. 끝이 없으면 null
   * @throws IllegalStateException 자격 증명이 10분 안에 끝나 쓸 수 있는 주소를 만들 수 없을 때
   */
  public static UrlPlan forMember(
      PhotoVariant variant,
      Instant now,
      Duration memberTtl,
      Duration shortTtl,
      Instant credentialsExpireAt) {
    Instant signedAt =
        variant == PhotoVariant.THUMB
            ? now.truncatedTo(ChronoUnit.HOURS)
            : now.truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt =
        usable(
            capped(
                signedAt.plus(variant == PhotoVariant.THUMB ? memberTtl : shortTtl),
                credentialsExpireAt),
            now,
            credentialsExpireAt);
    String cacheControl =
        variant == PhotoVariant.THUMB ? "private, max-age=" + maxAge(now, expiresAt) : null;
    return new UrlPlan(signedAt, expiresAt, cacheControl);
  }

  /**
   * 링크 열람자(가입 전 사람)가 받을 주소의 서명 시각·만료·캐시. 썸네일·원본이 같다.
   *
   * <p>링크 열람자는 재초대·숨김·연결·거주 종료로 언제든 권한을 잃으므로, 회원 썸네일처럼 정각에 서명해 주소를 재사용하지 않고 요청마다 지금 서명한다. 브라우저도 받은
   * 사진을 남기지 않게 {@code no-store}를 준다. 그래서 링크가 무효가 되면 이미 받은 주소도 {@code shortTtl} 안에 닫힌다(04 5-1 "보기",
   * 06 0-1 {@code photo.url.short} "원본, 링크 열람자").
   *
   * @param now 지금 서버 시각
   * @param shortTtl {@code photo.url.short}
   * @param credentialsExpireAt 서명에 쓸 자격 증명의 만료. 끝이 없으면 null
   * @throws IllegalStateException 자격 증명이 10분 안에 끝나 쓸 수 있는 주소를 만들 수 없을 때
   */
  public static UrlPlan forLink(Instant now, Duration shortTtl, Instant credentialsExpireAt) {
    Instant signedAt = now.truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt =
        usable(capped(signedAt.plus(shortTtl), credentialsExpireAt), now, credentialsExpireAt);
    return new UrlPlan(signedAt, expiresAt, LINK_CACHE);
  }

  /** 상한을 적용한 만료가 이미 지났으면 쓸 수 없는 주소라 만들지 않는다. */
  private static Instant usable(Instant expiresAt, Instant now, Instant credentialsExpireAt) {
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException("자격 증명이 곧 끝나 사진 주소를 만들 수 없어요: " + credentialsExpireAt);
    }
    return expiresAt;
  }

  private static Instant capped(Instant wanted, Instant credentialsExpireAt) {
    if (credentialsExpireAt == null) {
      return wanted;
    }
    Instant limit = credentialsExpireAt.minus(CREDENTIAL_MARGIN).truncatedTo(ChronoUnit.SECONDS);
    return limit.isBefore(wanted) ? limit : wanted;
  }

  /** 1시간과 주소의 남은 시간 중 짧은 쪽(초, 내림). 캐시가 주소보다 오래 남지 않게 한다(04 5-1). */
  private static long maxAge(Instant now, Instant expiresAt) {
    long remaining = Duration.between(now, expiresAt).getSeconds();
    return Math.min(MEMBER_THUMB_CACHE.getSeconds(), remaining);
  }
}
