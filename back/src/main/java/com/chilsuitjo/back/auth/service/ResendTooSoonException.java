package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;

/**
 * 다시 받기 간격이 남았다({@code RATE_LIMITED}, 06 ACC-1i). 응답의 {@code Retry-After}에 쓸 남은 시간을 함께 든다. 머리에 넣는
 * 일은 컨트롤러를 만들 때 한다.
 */
public class ResendTooSoonException extends BusinessException {

  private final Duration retryAfter;

  ResendTooSoonException(Duration retryAfter) {
    super(ErrorCode.RATE_LIMITED);
    this.retryAfter = retryAfter;
  }

  /** 남은 시간. 초 단위로 올림해 쓴다. */
  public Duration retryAfter() {
    return retryAfter;
  }
}
