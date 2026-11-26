package com.chilsuitjo.back.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * presigned GET 서명. 예제 값은 AWS 문서 "Authenticating Requests: Using Query Parameters (AWS Signature
 * Version 4)"의 예제(examplebucket, test.txt, 2013-05-24, 86400초)다.
 */
class S3PhotoUrlSignerTest {

  private static final URI EXAMPLE_BUCKET = URI.create("https://examplebucket.s3.amazonaws.com");
  private static final AwsBasicCredentials EXAMPLE_KEYS =
      AwsBasicCredentials.create(
          "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
  private static final Instant EXAMPLE_DATE = Instant.parse("2013-05-24T00:00:00Z");

  @Test
  void AWS_문서_예제와_같은_서명이_나온다() {
    String url =
        S3PhotoUrlSigner.sign(
            EXAMPLE_BUCKET,
            "us-east-1",
            EXAMPLE_KEYS,
            "test.txt",
            EXAMPLE_DATE,
            EXAMPLE_DATE.plus(Duration.ofSeconds(86400)),
            null);

    assertThat(url)
        .startsWith("https://examplebucket.s3.amazonaws.com/test.txt?")
        .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
        .contains(
            "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request")
        .contains("X-Amz-Date=20130524T000000Z")
        .contains("X-Amz-Expires=86400")
        .contains("X-Amz-SignedHeaders=host")
        .contains(
            "X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
  }

  @Test
  void 같은_키_시각_만료_캐시면_같은_주소다() {
    Instant signedAt = Instant.parse("2026-11-26T03:00:00Z");
    Instant expiresAt = Instant.parse("2026-11-26T05:00:00Z");

    String first = sign("photos/r/p/thumb.jpg", signedAt, expiresAt, "private, max-age=3600");
    String second = sign("photos/r/p/thumb.jpg", signedAt, expiresAt, "private, max-age=3600");

    assertThat(second).isEqualTo(first);
    assertThat(first).contains("X-Amz-Date=20261126T030000Z").contains("X-Amz-Expires=7200");
  }

  @Test
  void 캐시_헤더는_서명에_들어간다() {
    Instant signedAt = Instant.parse("2026-11-26T03:00:00Z");
    Instant expiresAt = Instant.parse("2026-11-26T05:00:00Z");

    String cached = sign("photos/r/p/thumb.jpg", signedAt, expiresAt, "private, max-age=3600");
    String plain = sign("photos/r/p/thumb.jpg", signedAt, expiresAt, null);

    assertThat(cached).contains("response-cache-control=private%2C%20max-age%3D3600");
    assertThat(signature(cached)).isNotEqualTo(signature(plain));
  }

  @Test
  void 임시_자격_증명이면_보안_토큰을_넣는다() {
    var session =
        AwsSessionCredentials.create(
            EXAMPLE_KEYS.accessKeyId(), EXAMPLE_KEYS.secretAccessKey(), "token+/=");

    String url =
        S3PhotoUrlSigner.sign(
            EXAMPLE_BUCKET,
            "us-east-1",
            session,
            "test.txt",
            EXAMPLE_DATE,
            EXAMPLE_DATE.plusSeconds(300),
            null);

    assertThat(url).contains("X-Amz-Security-Token=token%2B%2F%3D");
  }

  private static String sign(String key, Instant signedAt, Instant expiresAt, String cache) {
    return S3PhotoUrlSigner.sign(
        URI.create("https://homestamp-photos.s3.ap-northeast-2.amazonaws.com"),
        "ap-northeast-2",
        EXAMPLE_KEYS,
        key,
        signedAt,
        expiresAt,
        cache);
  }

  private static String signature(String url) {
    return url.replaceAll(".*X-Amz-Signature=([0-9a-f]+).*", "$1");
  }
}
