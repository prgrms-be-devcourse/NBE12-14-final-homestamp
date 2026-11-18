package com.chilsuitjo.back.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeMemberQueryServiceTest {

  private final FakeMemberQueryService service = new FakeMemberQueryService();
  private final UUID memberId = UUID.randomUUID();

  @Test
  void 회원의_이름은_가려서_돌려받는다() {
    service.seed(memberId, "정서윤");

    assertThat(service.maskedName(memberId)).contains("정*윤");
  }

  @Test
  void 탈퇴한_회원은_비어_있다() {
    service.seedWithdrawn(memberId);

    assertThat(service.maskedName(memberId)).isEmpty();
  }

  @Test
  void 없는_회원은_NOT_FOUND() {
    assertThatThrownBy(() -> service.maskedName(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }
}
