package com.chilsuitjo.back.photo.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 사진 파일을 내려받을 presigned GET 주소를 만든다(04 5-1 "보기"). 서명은 서버 안의 계산이라 AWS를 부르지 않는다. 사진 버킷이 있으면 {@link
 * S3PhotoUrlSigner}, 없으면(local·test) {@link FakePhotoUrlSigner}다.
 */
public interface PhotoUrlSigner {

  /** 지금 서명에 쓸 자격 증명의 만료. 끝이 없는 자격 증명(고정 키·가짜)이면 빈 값. */
  Optional<Instant> credentialsExpireAt();

  /**
   * @param key {@code photos/} 아래의 객체 키
   * @param signedAt 서명 시각({@code X-Amz-Date}). 같은 값을 넣으면 같은 주소가 나온다
   * @param expiresAt 주소가 끝나는 시각. {@code signedAt}보다 뒤다
   * @param cacheControl 사진을 받을 때 돌려줄 {@code Cache-Control}({@code response-cache-control}). 없으면
   *     null
   */
  String presignGet(String key, Instant signedAt, Instant expiresAt, String cacheControl);
}
