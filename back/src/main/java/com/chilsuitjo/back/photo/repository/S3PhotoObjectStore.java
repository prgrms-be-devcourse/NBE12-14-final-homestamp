package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.config.ConditionalOnPhotoBucket;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link PhotoObjectStore}의 S3 구현. 검사·복사는 ETag 조건을 걸어 같은 파일일 때만 한다(04 5-1 "조건부 처리"). */
@Repository
@ConditionalOnPhotoBucket
public class S3PhotoObjectStore implements PhotoObjectStore {

  private static final int PRECONDITION_FAILED = 412;
  private static final int NOT_FOUND = 404;

  private final S3Client s3;
  private final String bucket;

  S3PhotoObjectStore(S3Client s3, @Value("${photo.s3.bucket}") String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public Optional<ObjectHead> head(String key) {
    try {
      var head = s3.headObject(r -> r.bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED));
      return Optional.of(
          new ObjectHead(
              head.eTag(), head.contentLength(), head.contentType(), head.checksumSHA256()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public byte[] readStart(String key, String eTag, int bytes) {
    try {
      return s3.getObjectAsBytes(
              r -> r.bucket(bucket).key(key).range("bytes=0-" + (bytes - 1)).ifMatch(eTag))
          .asByteArray();
    } catch (S3Exception e) {
      throw changedOr(e, key);
    }
  }

  @Override
  public void copy(String sourceKey, String targetKey, String eTag) {
    try {
      s3.copyObject(r ->
          r.sourceBucket(bucket)
              .sourceKey(sourceKey)
              .destinationBucket(bucket)
              .destinationKey(targetKey)
              .copySourceIfMatch(eTag)
              .checksumAlgorithm(ChecksumAlgorithm.SHA256));
    } catch (S3Exception e) {
      throw changedOr(e, sourceKey);
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(r -> r.bucket(bucket).key(key));
  }

  private static RuntimeException changedOr(S3Exception e, String key) {
    if (e.statusCode() == PRECONDITION_FAILED || e.statusCode() == NOT_FOUND) {
      return new ObjectChangedException(key);
    }
    return e;
  }
}
