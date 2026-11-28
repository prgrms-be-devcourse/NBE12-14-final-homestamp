package com.chilsuitjo.back.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessExceptionTest {

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {"UPLOAD_TICKET_INVALID", "UPLOAD_TICKET_EXPIRED", "PHOTO_CHECK_FAILED"})
  void 사진_업로드_오류는_사진_번호를_받는다(ErrorCode code) {
    BusinessException exception = new BusinessException(code, 0);

    assertThat(exception.errorCode()).isEqualTo(code);
    assertThat(exception.photoIndex()).contains(0);
    assertThat(exception.retryAfter()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {"UPLOAD_TICKET_INVALID", "UPLOAD_TICKET_EXPIRED", "PHOTO_CHECK_FAILED"},
      mode = EnumSource.Mode.EXCLUDE)
  void 다른_오류에는_사진_번호를_넣을_수_없다(ErrorCode code) {
    assertThatThrownBy(() -> new BusinessException(code, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {"UPLOAD_TICKET_INVALID", "UPLOAD_TICKET_EXPIRED", "PHOTO_CHECK_FAILED"})
  void 사진_업로드_오류라도_음수_번호는_받지_않는다(ErrorCode code) {
    assertThatThrownBy(() -> new BusinessException(code, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 중복_지불은_기존_기록과_비슷한_기록인지를_함께_싣는다(boolean similar) {
    UUID existing = UUID.randomUUID();

    BusinessException exception = BusinessException.duplicatePayment(existing, similar);

    assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_PAYMENT);
    assertThat(exception.duplicate()).contains(new BusinessException.Duplicate(existing, similar));
    assertThat(exception.photoIndex()).isEmpty();
    assertThat(exception.retryAfter()).isEmpty();
  }
}
