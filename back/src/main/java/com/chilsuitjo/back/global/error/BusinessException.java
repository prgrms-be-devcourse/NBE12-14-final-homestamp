package com.chilsuitjo.back.global.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 규칙에 맞지 않는 요청. 모든 도메인이 이 예외 하나로 {@link ErrorCode}를 던진다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Duration retryAfter;
  private final Integer photoIndex;
  private final Duplicate duplicate;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = null;
    this.photoIndex = null;
    this.duplicate = null;
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
    this.duplicate = null;
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
    this.duplicate = null;
  }

  private BusinessException(ErrorCode errorCode, Duplicate duplicate) {
    super(errorCode.name());
    this.errorCode = errorCode;
    this.retryAfter = null;
    this.photoIndex = null;
    this.duplicate = duplicate;
  }

  /**
   * 같은 지불이 이미 있다(06 MONEY-1a·1c, {@code DUPLICATE_PAYMENT}). 화면이 그 기록을 열 수 있게 기존 지불 ID를 함께 알린다.
   *
   * @param existingId 이미 있는 지불
   * @param similar 30일 안 비슷한 기록이라 {@code similarConfirmed: true}로 다시 보내면 남길 수 있는지(MONEY-1c). false면
   *     상대가 남긴 확인 전 기록이라 새로 만들 수 없고 그 기록을 확인하게 한다(MONEY-1a)
   */
  public static BusinessException duplicatePayment(UUID existingId, boolean similar) {
    return new BusinessException(ErrorCode.DUPLICATE_PAYMENT, new Duplicate(existingId, similar));
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

  public Optional<Duplicate> duplicate() {
    return Optional.ofNullable(duplicate);
  }

  /**
   * 이미 있는 기록(응답의 {@code duplicateOf}·{@code similar}).
   *
   * @param existingId 이미 있는 기록
   * @param similar 확인하고 다시 보내면 남길 수 있는지
   */
  public record Duplicate(UUID existingId, boolean similar) {

    public Duplicate {
      Objects.requireNonNull(existingId, "existingId");
    }
  }
}
