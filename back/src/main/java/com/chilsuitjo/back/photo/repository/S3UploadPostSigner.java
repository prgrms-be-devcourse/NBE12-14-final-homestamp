package com.chilsuitjo.back.photo.repository;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import com.chilsuitjo.back.photo.dto.PresignedPostResponse;
import com.chilsuitjo.back.photo.rule.PhotoUploadFileRules;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;
import tools.jackson.databind.json.JsonMapper;

@Repository
@ConditionalOnPhotoBucket
public class S3UploadPostSigner implements UploadPostSigner {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final String HMAC = "HmacSHA256";
  private static final String REQUEST_SCOPE = "aws4_request";

  private final S3Client s3Client;
  private final Clock clock;
  private final String bucket;
  private final Duration policyTtl;
  private final long maxFileBytes;

  public S3UploadPostSigner(
      S3Client s3Client,
      Clock clock,
      @Value("${photo.s3.bucket}") String bucket,
      @Value("${upload.policy.ttl}") Duration policyTtl,
      @Value("${upload.size.max}") DataSize maxFileSize) {
    this.s3Client = s3Client;
    this.clock = clock;
    this.bucket = bucket;
    this.policyTtl = policyTtl;
    this.maxFileBytes = maxFileSize.toBytes();
  }

  /** 브라우저가 임시 사진 한 파일을 직접 올릴 S3 주소와 서명된 폼 필드를 만든다. */
  @Override
  public PresignedPostResponse createPresignedPost(
      String pendingKey, long fileBytes, String sha256Base64) {
    if (pendingKey == null || !pendingKey.startsWith("pending/") || pendingKey.equals("pending/")) {
      throw new IllegalArgumentException("사진 객체 키는 pending/ 아래에 있어야 해요.");
    }
    if (fileBytes < 1 || fileBytes > maxFileBytes) {
      throw new IllegalArgumentException("사진 크기가 업로드 범위를 벗어났어요.");
    }
    if (!PhotoUploadFileRules.isSha256Base64(sha256Base64)) {
      throw new IllegalArgumentException("사진의 SHA-256 체크섬은 32바이트의 표준 Base64여야 해요.");
    }

    var config = s3Client.serviceClientConfiguration();
    var region = config.region();
    var credentials = config.credentialsProvider().resolveIdentity().join();
    Instant now = clock.instant();
    Instant policyExpiration = now.plus(policyTtl);
    // 임시 자격 증명이 끝나면 서명도 무효가 되므로, 두 만료 시각 중 이른 값을 쓴다.
    Instant expiresAt =
        credentials
            .expirationTime()
            .filter(expiration -> expiration.isBefore(policyExpiration))
            .orElse(policyExpiration);
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException("만료된 자격 증명으로 사진 업로드 정책을 발급할 수 없어요.");
    }

    String timestamp = AMZ_DATE.format(now);
    String date = timestamp.substring(0, 8);
    String scope = date + "/" + region.id() + "/s3/" + REQUEST_SCOPE;
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", pendingKey);
    fields.put("Content-Type", "image/jpeg");
    fields.put("x-amz-checksum-algorithm", "SHA256");
    fields.put("x-amz-checksum-sha256", sha256Base64);
    fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
    fields.put("x-amz-credential", credentials.accessKeyId() + "/" + scope);
    fields.put("x-amz-date", timestamp);
    if (credentials instanceof AwsSessionCredentialsIdentity session) {
      fields.put("x-amz-security-token", session.sessionToken());
    }

    // S3가 폼 필드를 검사할 조건을 먼저 담고, Base64로 인코딩한 정책에 서명한다.
    String policy =
        Base64.getEncoder().encodeToString(createPolicyJson(fields, expiresAt).getBytes(UTF_8));
    fields.put("policy", policy);
    fields.put(
        "x-amz-signature", signPolicy(credentials.secretAccessKey(), date, region.id(), policy));

    String url =
        S3EndpointProvider.defaultProvider()
            .resolveEndpoint(params -> params.bucket(bucket).region(region))
            .join()
            .endpointUrl()
            .toUri()
            .toString();
    return new PresignedPostResponse(url, Map.copyOf(fields), expiresAt);
  }

  private String createPolicyJson(Map<String, String> fields, Instant expiresAt) {
    List<Object> conditions = new ArrayList<>();
    conditions.add(Map.of("bucket", bucket));
    fields.forEach((name, value) -> conditions.add(Map.of(name, value)));
    conditions.add(List.of("content-length-range", 1, maxFileBytes));
    return JSON.writeValueAsString(
        Map.of("expiration", expiresAt.toString(), "conditions", conditions));
  }

  /** AWS 비밀 키에서 날짜·리전·서비스 범위의 서명 키를 파생해 Base64 정책에 서명한다. */
  static String signPolicy(String secret, String date, String region, String policy) {
    byte[] dateKey = hmac(("AWS4" + secret).getBytes(UTF_8), date);
    byte[] regionKey = hmac(dateKey, region);
    byte[] serviceKey = hmac(regionKey, "s3");
    byte[] signingKey = hmac(serviceKey, REQUEST_SCOPE);
    return HexFormat.of().formatHex(hmac(signingKey, policy));
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      return mac.doFinal(value.getBytes(UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("사진 업로드 정책을 서명할 수 없어요.", e);
    }
  }
}
