package com.chilsuitjo.back.photo.rule;

import java.util.Base64;

/** S3에 보낼 사진 파일 정보의 형식을 확인한다(04 5-1 "지문값"). */
public final class PhotoUploadFileRules {

  /**
   * 한 번에 고르는 사진 수 {@code upload.batch.max}(06 0-1). 요청 검증 어노테이션({@code @Size})에 넣어야 해서 설정이 아니라 상수다.
   */
  public static final int MAX_BATCH = 10;

  private static final int SHA256_BYTES = 32;
  private static final int SHA256_BASE64_LENGTH = 44;

  private PhotoUploadFileRules() {}

  public static boolean isSha256Base64(String checksum) {
    if (checksum == null || checksum.length() != SHA256_BASE64_LENGTH) {
      return false;
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(checksum);
      return bytes.length == SHA256_BYTES
          && Base64.getEncoder().encodeToString(bytes).equals(checksum);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
