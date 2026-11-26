package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import com.chilsuitjo.back.photo.dto.PresignedPostResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Repository;

/**
 * 사진 버킷이 없는 local·test용 가짜 업로드 폼. AWS 자격 증명 없이 업로드 표 발급까지 확인할 수 있게, 서명 없이 키·형식·체크섬 필드만 담아 돌려준다. 주소는
 * 닿지 않는 {@code .invalid} 도메인이라 실제로 올릴 수는 없다.
 *
 * <p>{@code prod}에서 버킷이 비면 조용히 가짜로 돌지 않도록 시작하지 않는다.
 */
@Repository
@ConditionalOnPhotoBucket(false)
public class FakeUploadPostSigner implements UploadPostSigner {

  static final String URL = "https://fake-photo-storage.invalid/";

  private final Clock clock;
  private final Duration policyTtl;

  public FakeUploadPostSigner(
      Clock clock, @Value("${upload.policy.ttl}") Duration policyTtl, Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "prod에는 사진 버킷이 있어야 한다. " + ConditionalOnPhotoBucket.BUCKET + "를 넣어야 시작한다");
    }
    this.clock = clock;
    this.policyTtl = policyTtl;
  }

  @Override
  public PresignedPostResponse createPresignedPost(
      String pendingKey, long fileBytes, String sha256Base64) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", pendingKey);
    fields.put("Content-Type", "image/jpeg");
    fields.put("x-amz-checksum-algorithm", "SHA256");
    fields.put("x-amz-checksum-sha256", sha256Base64);
    return new PresignedPostResponse(URL, Map.copyOf(fields), clock.instant().plus(policyTtl));
  }
}
