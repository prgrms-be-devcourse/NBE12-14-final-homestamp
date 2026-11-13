package com.chilsuitjo.back.global.error;

/** 규칙에 맞지 않는 요청. 모든 도메인이 이 예외 하나로 {@link ErrorCode}를 던진다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
