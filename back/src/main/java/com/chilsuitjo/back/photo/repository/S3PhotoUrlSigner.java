package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

/**
 * S3 presigned GET(04 5-1 "보기"). SDK의 SigV4 서명기({@link AwsV4HttpSigner})를 쿼리 문자열 방식으로 부른다. {@code
 * S3Presigner}는 서명 시각을 지금으로만 정해서, 회원 썸네일을 정각에 서명할 수 없어 쓰지 않는다.
 *
 * <p>서명 시각을 고정 시계({@link HttpSigner#SIGNING_CLOCK})로 넘기므로 같은 키·시각·만료·캐시면 같은 주소가 나온다. 임시 자격 증명이면
 * 서명기가 {@code X-Amz-Security-Token}을 더한다. 서명은 서버 안의 계산이라 AWS를 부르지 않는다.
 */
@Repository
@ConditionalOnPhotoBucket
public class S3PhotoUrlSigner implements PhotoUrlSigner {

  private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();

  private final S3Client s3Client;
  private final String bucket;

  public S3PhotoUrlSigner(S3Client s3Client, @Value("${photo.s3.bucket}") String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  @Override
  public Optional<Instant> credentialsExpireAt() {
    return credentials().expirationTime();
  }

  @Override
  public String presignGet(String key, Instant signedAt, Instant expiresAt, String cacheControl) {
    var region = s3Client.serviceClientConfiguration().region();
    URI endpoint =
        S3EndpointProvider.defaultProvider()
            .resolveEndpoint(params -> params.bucket(bucket).region(region))
            .join()
            .endpointUrl()
            .toUri();
    return sign(endpoint, region.id(), credentials(), key, signedAt, expiresAt, cacheControl);
  }

  private AwsCredentialsIdentity credentials() {
    return s3Client.serviceClientConfiguration().credentialsProvider().resolveIdentity().join();
  }

  /**
   * 버킷 주소({@code endpoint}) 아래 {@code key}의 GET 주소에 서명한다. AWS 문서의 예제 값으로 단위 테스트한다.
   *
   * @param cacheControl {@code response-cache-control}로 서명에 넣는다. 없으면 null
   */
  static String sign(
      URI endpoint,
      String region,
      AwsCredentialsIdentity credentials,
      String key,
      Instant signedAt,
      Instant expiresAt,
      String cacheControl) {
    if (key == null || key.isBlank() || key.startsWith("/")) {
      throw new IllegalArgumentException("사진 객체 키가 비었거나 /로 시작해요: " + key);
    }
    if (!expiresAt.isAfter(signedAt)) {
      throw new IllegalArgumentException("사진 주소의 만료는 서명 시각보다 뒤여야 해요.");
    }
    String basePath = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
    if (basePath.endsWith("/")) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    SdkHttpRequest.Builder request =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.GET)
            .protocol(endpoint.getScheme())
            .host(endpoint.getHost())
            .port(endpoint.getPort() == -1 ? null : endpoint.getPort())
            .encodedPath(basePath + "/" + SdkHttpUtils.urlEncodeIgnoreSlashes(key));
    if (cacheControl != null) {
      request.putRawQueryParameter("response-cache-control", cacheControl);
    }
    return SIGNER
        .sign(r ->
            r.identity(credentials)
                .request(request.build())
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
                .putProperty(
                    AwsV4HttpSigner.EXPIRATION_DURATION, Duration.between(signedAt, expiresAt))
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC)))
        .request()
        .getUri()
        .toString();
  }
}
