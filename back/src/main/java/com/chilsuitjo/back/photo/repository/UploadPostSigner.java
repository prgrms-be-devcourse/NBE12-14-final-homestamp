package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.photo.dto.PresignedPostResponse;

/**
 * 브라우저가 사진 한 파일을 저장소에 직접 올릴 presigned POST를 만든다(04 5-1 "올리기"). 사진 버킷이 있으면 {@link
 * S3UploadPostSigner}, 없으면(local·test) {@link FakeUploadPostSigner}다.
 */
public interface UploadPostSigner {

  /**
   * @param pendingKey {@code pending/} 아래의 객체 키
   * @param fileBytes 파일 크기(1바이트 ~ {@code upload.size.max})
   * @param sha256Base64 파일 SHA-256의 표준 Base64(32바이트)
   */
  PresignedPostResponse createPresignedPost(String pendingKey, long fileBytes, String sha256Base64);
}
