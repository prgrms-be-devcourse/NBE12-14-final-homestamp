package com.chilsuitjo.back.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 사진 저장소와 업로드 서명에서 사용할 S3 클라이언트를 등록한다. 사진 버킷이 없으면(local·test) 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnPhotoBucket
public class S3Config {

  @Bean
  S3Client s3Client(@Value("${photo.s3.region}") String region) {
    // AWS SDK의 기본 자격 증명 체인이 로컬 환경변수·프로필이나 운영 EC2 역할을 사용한다.
    return S3Client.builder().region(Region.of(region)).build();
  }
}
