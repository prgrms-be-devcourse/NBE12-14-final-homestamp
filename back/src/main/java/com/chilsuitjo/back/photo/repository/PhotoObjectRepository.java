package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

@Repository
@ConditionalOnPhotoBucket
public class PhotoObjectRepository {

  private final S3Client s3Client;
  private final String bucket;

  public PhotoObjectRepository(S3Client s3Client, @Value("${photo.s3.bucket}") String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  /** 연결 확인용으로 임시 업로드 키를 최대 10개 조회한다. */
  public List<String> listPendingKeys() {
    return s3Client
        .listObjectsV2(request -> request.bucket(bucket).prefix("pending/").maxKeys(10))
        .contents()
        .stream()
        .map(S3Object::key)
        .toList();
  }
}
