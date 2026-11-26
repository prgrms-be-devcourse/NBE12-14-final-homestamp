package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Repository;

/**
 * 사진 버킷이 없는 local·test용 가짜 사진 주소. 서명 없이 키·서명 시각·만료·캐시를 주소에 적는다. 같은 값을 넣으면 같은 주소가 나온다. 닿지 않는 {@code
 * .invalid} 도메인이라 실제로 받을 수는 없다. 자격 증명이 없어 만료 상한도 없다.
 *
 * <p>{@code prod}에서 버킷이 비면 조용히 가짜로 돌지 않도록 시작하지 않는다.
 */
@Repository
@ConditionalOnPhotoBucket(false)
public class FakePhotoUrlSigner implements PhotoUrlSigner {

  static final String URL = "https://fake-photo-storage.invalid/";

  public FakePhotoUrlSigner(Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "prod에는 사진 버킷이 있어야 한다. " + ConditionalOnPhotoBucket.BUCKET + "를 넣어야 시작한다");
    }
  }

  @Override
  public Optional<Instant> credentialsExpireAt() {
    return Optional.empty();
  }

  @Override
  public String presignGet(String key, Instant signedAt, Instant expiresAt, String cacheControl) {
    String url = URL + key + "?signedAt=" + signedAt + "&expiresAt=" + expiresAt;
    return cacheControl == null
        ? url
        : url + "&cacheControl=" + URLEncoder.encode(cacheControl, StandardCharsets.UTF_8);
  }
}
