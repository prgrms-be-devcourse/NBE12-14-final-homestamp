package com.chilsuitjo.back.global.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 규칙에 맞지 않는 요청. 모든 도메인이 이 예외 하나로 {@link ErrorCode}를 던진다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Duration retryAfter;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = null;
  }

  /**
   * 다시 해도 되는 때까지 남은 시간을 함께 알린다(06 4-10 {@code RATE_LIMITED}·{@code UPLOAD_LIMIT}의 {@code
   * Retry-After}). 응답 머리에는 초 단위로 올림해 넣는다.
   */
  public BusinessException(ErrorCode errorCode, Duration retryAfter) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = Objects.requireNonNull(retryAfter);
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
