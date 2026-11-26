package com.chilsuitjo.back.photo.repository;

import java.util.Optional;

/**
 * 사진 파일 저장소(S3) 중 사진 붙이기가 쓰는 부분(04 5-1 "제출 처리"). 검사와 복사는 같은 파일일 때만 하도록 ETag를 조건으로 건다: 검사 뒤 같은 키로 다시
 * 올려 바꿔치기하면 {@link ObjectChangedException}이 난다.
 */
public interface PhotoObjectStore {

  /** 파일 정보. 없으면 빈 값. 체크섬은 {@code ChecksumMode=ENABLED}로 받은 SHA-256 base64다. */
  Optional<ObjectHead> head(String key);

  /**
   * 앞부분 {@code bytes}바이트를 {@code If-Match: eTag}로 읽는다. 파일이 그보다 짧으면 있는 만큼 돌려준다.
   *
   * @throws ObjectChangedException ETag가 달라졌거나 파일이 없어졌을 때
   */
  byte[] readStart(String key, String eTag, int bytes);

  /**
   * {@code x-amz-copy-source-if-match: eTag}로 복사한다. 체크섬은 따라온다.
   *
   * @throws ObjectChangedException ETag가 달라졌거나 원본이 없어졌을 때
   */
  void copy(String sourceKey, String targetKey, String eTag);

  /** 지운다. 없는 키여도 오류가 아니다. */
  void delete(String key);

  /**
   * @param eTag 조건부 읽기·복사에 쓰는 값
   * @param sizeBytes 파일 크기
   * @param contentType 업로드 폼에 적힌 형식
   * @param sha256Base64 S3가 검증한 SHA-256(base64). 없으면 null
   */
  record ObjectHead(String eTag, long sizeBytes, String contentType, String sha256Base64) {}

  /** 조건(ETag)이 맞지 않아 S3가 412로 거절했거나, 그사이 파일이 없어졌다. */
  final class ObjectChangedException extends RuntimeException {
    public ObjectChangedException(String key) {
      super("사진 파일이 검사 뒤 바뀌었어요: " + key);
    }
  }
}
