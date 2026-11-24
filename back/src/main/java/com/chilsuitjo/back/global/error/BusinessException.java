package com.chilsuitjo.back.global.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 규칙에 맞지 않는 요청. 모든 도메인이 이 예외 하나로 {@link ErrorCode}를 던진다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Duration retryAfter;
  private final Integer photoIndex;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = null;
    this.photoIndex = null;
  }

  /**
   * 다시 해도 되는 때까지 남은 시간을 함께 알린다(06 4-10 {@code RATE_LIMITED}·{@code UPLOAD_LIMIT}의 {@code
   * Retry-After}). 응답 머리에는 초 단위로 올림해 넣는다.
   */
  public BusinessException(ErrorCode errorCode, Duration retryAfter) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = Objects.requireNonNull(retryAfter);
    this.photoIndex = null;
  }

  /**
   * 실패한 사진의 업로드 표 목록 위치를 함께 알린다. 첫 사진은 0이다.
   *
   * @throws IllegalArgumentException 사진 업로드 표·검사 오류가 아니거나 사진 번호가 음수인 경우
   */
  public BusinessException(ErrorCode errorCode, int photoIndex) {
    super(errorCode.name());
    switch (errorCode) {
      case UPLOAD_TICKET_INVALID, UPLOAD_TICKET_EXPIRED, PHOTO_CHECK_FAILED -> {}
      default -> throw new IllegalArgumentException("사진 번호는 사진 업로드 표·검사 오류에만 넣을 수 있습니다");
    }
    if (photoIndex < 0) {
      throw new IllegalArgumentException("사진 번호는 0 이상이어야 합니다");
    }
    this.errorCode = errorCode;
    this.retryAfter = null;
    this.photoIndex = photoIndex;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }

  public Optional<Integer> photoIndex() {
    return Optional.ofNullable(photoIndex);
  }
}
