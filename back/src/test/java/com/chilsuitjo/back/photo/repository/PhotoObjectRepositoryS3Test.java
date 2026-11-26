package com.chilsuitjo.back.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.config.S3Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig({S3Config.class, PhotoObjectRepository.class})
@TestPropertySource(
    properties = {
      "photo.s3.bucket=${PHOTO_S3_BUCKET}",
      "photo.s3.region=${PHOTO_S3_REGION:ap-northeast-2}"
    })
@EnabledIfEnvironmentVariable(named = "PHOTO_S3_BUCKET", matches = "\\S+")
class PhotoObjectRepositoryS3Test {

  @Autowired private PhotoObjectRepository repository;

  @Test
  void listsPendingKeys() {
    assertThat(repository.listPendingKeys())
        .hasSizeLessThanOrEqualTo(10)
        .allSatisfy(key -> assertThat(key).startsWith("pending/"));
  }
}
