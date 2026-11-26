package com.chilsuitjo.back.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

class S3UploadPostSignerTest {

  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
  private static final String BUCKET = "example-photos";
  private static final String KEY = "pending/room-1/original.jpg";
  private static final String CHECKSUM = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String EXAMPLE_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void matchesAwsPostSignatureExample() {
    // AWS S3 Developer Guide: Example: Browser-Based Upload using HTTP POST.
    String policy =
        "eyAiZXhwaXJhdGlvbiI6ICIyMDE1LTEyLTMwVDEyOjAwOjAwLjAwMFoiLA0K"
            + "ICAiY29uZGl0aW9ucyI6IFsNCiAgICB7ImJ1Y2tldCI6ICJzaWd2NGV4YW1wbGVidWNrZXQifSwNCiAgICBb"
            + "InN0YXJ0cy13aXRoIiwgIiRrZXkiLCAidXNlci91c2VyMS8iXSwNCiAgICB7ImFjbCI6ICJwdWJsaWMt"
            + "cmVhZCJ9LA0KICAgIHsic3VjY2Vzc19hY3Rpb25fcmVkaXJlY3QiOiAiaHR0cDovL3NpZ3Y0ZXhhbXBsZWJ1"
            + "Y2tldC5zMy5hbWF6b25hd3MuY29tL3N1Y2Nlc3NmdWxfdXBsb2FkLmh0bWwifSwNCiAgICBbInN0YXJ0"
            + "cy13aXRoIiwgIiRDb250ZW50LVR5cGUiLCAiaW1hZ2UvIl0sDQogICAgeyJ4LWFtei1tZXRhLXV1aWQi"
            + "OiAiMTQzNjUxMjM2NTEyNzQifSwNCiAgICB7IngtYW16LXNlcnZlci1zaWRlLWVuY3J5cHRpb24iOiAi"
            + "QUVTMjU2In0sDQogICAgWyJzdGFydHMtd2l0aCIsICIkeC1hbXotbWV0YS10YWciLCAiIl0sDQoNCiAgICB7"
            + "IngtYW16LWNyZWRlbnRpYWwiOiAiQUtJQUlPU0ZPRE5ON0VYQU1QTEUvMjAxNTEyMjkvdXMtZWFzdC0x"
            + "L3MzL2F3czRfcmVxdWVzdCJ9LA0KICAgIHsieC1hbXotYWxnb3JpdGhtIjogIkFXUzQtSE1BQy1TSEEy"
            + "NTYifSwNCiAgICB7IngtYW16LWRhdGUiOiAiMjAxNTEyMjlUMDAwMDAwWiIgfQ0KICBdDQp9";

    assertThat(S3UploadPostSigner.signPolicy(EXAMPLE_SECRET, "20151229", "us-east-1", policy))
        .isEqualTo("8afdbf4008c03f22c2cd3cdb72e4afbb1f6a588f3255ac628749a66d7f09699e");
  }

  @Test
  void signsTheFormFieldsAndUploadLimits() {
    try (S3Client client = client(AwsBasicCredentials.create("example-access", EXAMPLE_SECRET))) {
      var post = signer(client).createPresignedPost(KEY, 100, CHECKSUM);
      var uri = URI.create(post.url());
      assertThat(uri.getScheme()).isEqualTo("https");
      assertThat(uri.getHost()).isEqualTo("example-photos.s3.ap-northeast-2.amazonaws.com");
      assertThat(post.expiresAt()).isEqualTo(NOW.plusSeconds(300));
      assertThat(post.fields())
          .containsEntry("key", KEY)
          .containsEntry("Content-Type", "image/jpeg")
          .containsEntry("x-amz-checksum-algorithm", "SHA256")
          .containsEntry("x-amz-checksum-sha256", CHECKSUM)
          .containsEntry("x-amz-algorithm", "AWS4-HMAC-SHA256")
          .containsEntry("x-amz-date", "20261125T000000Z")
          .containsEntry(
              "x-amz-credential", "example-access/20261125/ap-northeast-2/s3/aws4_request")
          .doesNotContainKey("x-amz-security-token")
          .doesNotContainValue(EXAMPLE_SECRET);

      String encodedPolicy = post.fields().get("policy");
      var policy = JSON.readTree(Base64.getDecoder().decode(encodedPolicy));
      var conditions = policy.get("conditions");
      assertThat(policy.get("expiration").asString()).isEqualTo(post.expiresAt().toString());
      assertThat(conditions.findValue("bucket").asString()).isEqualTo(BUCKET);
      post.fields()
          .forEach((name, value) -> {
            if (!name.equals("policy") && !name.equals("x-amz-signature")) {
              assertThat(conditions.findValue(name).asString()).isEqualTo(value);
            }
          });
      assertThat(conditions).contains(JSON.readTree("[\"content-length-range\",1,10485760]"));
      assertThat(post.fields().get("x-amz-signature"))
          .isEqualTo(
              S3UploadPostSigner.signPolicy(
                  EXAMPLE_SECRET, "20261125", "ap-northeast-2", encodedPolicy));
    }
  }

  @Test
  void signsTheSessionTokenAndCapsExpirationAtCredentialExpiry() {
    Instant credentialExpiry = NOW.plusSeconds(120);
    var credentials =
        AwsSessionCredentials.builder()
            .accessKeyId("example-access")
            .secretAccessKey(EXAMPLE_SECRET)
            .sessionToken("example-token")
            .expirationTime(credentialExpiry)
            .build();
    try (S3Client client = client(credentials)) {
      var post = signer(client).createPresignedPost(KEY, 100, CHECKSUM);
      var policy = JSON.readTree(Base64.getDecoder().decode(post.fields().get("policy")));

      assertThat(post.expiresAt()).isEqualTo(credentialExpiry);
      assertThat(post.fields()).containsEntry("x-amz-security-token", "example-token");
      assertThat(policy.get("conditions").findValue("x-amz-security-token").asString())
          .isEqualTo("example-token");
    }
  }

  private static S3Client client(AwsCredentials credentials) {
    return S3Client.builder()
        .region(Region.AP_NORTHEAST_2)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .build();
  }

  private static S3UploadPostSigner signer(S3Client client) {
    return new S3UploadPostSigner(
        client,
        Clock.fixed(NOW, ZoneOffset.UTC),
        BUCKET,
        Duration.ofMinutes(5),
        DataSize.ofMegabytes(10));
  }
}
