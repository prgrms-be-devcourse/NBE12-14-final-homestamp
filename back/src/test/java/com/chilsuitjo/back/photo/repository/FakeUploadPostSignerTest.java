package com.chilsuitjo.back.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.S3Config;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

class FakeUploadPostSignerTest {

  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
  private static final String KEY = "pending/room-1/original.jpg";
  private static final String CHECKSUM = Base64.getEncoder().encodeToString(new byte[32]);

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          // 앱처럼 "5m"·"10MB"를 Duration·DataSize로 바꾸게 한다.
          .withInitializer(app ->
              app.getBeanFactory()
                  .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(
              Clocks.class, S3Config.class, S3UploadPostSigner.class, FakeUploadPostSigner.class)
          .withPropertyValues(
              "photo.s3.region=ap-northeast-2", "upload.policy.ttl=5m", "upload.size.max=10MB");

  @Test
  void usesFakeWithoutBucket() {
    context.run(app -> {
      assertThat(app).hasSingleBean(UploadPostSigner.class);
      assertThat(app.getBean(UploadPostSigner.class)).isInstanceOf(FakeUploadPostSigner.class);
      assertThat(app).doesNotHaveBean(S3Client.class);
    });
  }

  @Test
  void usesS3WithBucket() {
    context
        .withPropertyValues("photo.s3.bucket=example-photos")
        .run(app -> {
          assertThat(app).hasSingleBean(UploadPostSigner.class);
          assertThat(app.getBean(UploadPostSigner.class)).isInstanceOf(S3UploadPostSigner.class);
        });
  }

  @Test
  void refusesToStartFakeInProd() {
    context
        .withPropertyValues("spring.profiles.active=prod")
        .run(app ->
            assertThat(app).getFailure().rootCause().hasMessageContaining("prod에는 사진 버킷이 있어야 한다"));
  }

  @Test
  void returnsUnsignedFormWithPolicyExpiry() {
    context.run(app -> {
      var post = app.getBean(UploadPostSigner.class).createPresignedPost(KEY, 100, CHECKSUM);
      assertThat(post.url()).isEqualTo(FakeUploadPostSigner.URL);
      assertThat(post.expiresAt()).isEqualTo(NOW.plusSeconds(300));
      assertThat(post.fields())
          .containsEntry("key", KEY)
          .containsEntry("Content-Type", "image/jpeg")
          .containsEntry("x-amz-checksum-sha256", CHECKSUM)
          .doesNotContainKeys("policy", "x-amz-signature");
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class Clocks {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
