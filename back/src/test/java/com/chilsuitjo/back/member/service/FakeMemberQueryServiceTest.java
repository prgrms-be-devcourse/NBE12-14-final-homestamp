package com.chilsuitjo.back.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  @Test
  void 여러_회원의_이름을_한_번에_가려서_돌려받는다() {
    UUID other = UUID.randomUUID();
    UUID withdrawn = UUID.randomUUID();
    service.seed(memberId, "정서윤");
    service.seed(other, "남궁민수");
    service.seedWithdrawn(withdrawn);

    Map<UUID, Optional<String>> names = service.maskedNames(List.of(memberId, other, withdrawn));

    assertThat(names)
        .containsOnly(
            entry(memberId, Optional.of("정*윤")),
            entry(other, Optional.of("남**수")),
            entry(withdrawn, Optional.empty()));
  }

  @Test
  void 빈_목록이면_빈_맵() {
    assertThat(service.maskedNames(List.of())).isEmpty();
  }

  @Test
  void 같은_회원이_여러_번_와도_한_번만_담는다() {
    service.seed(memberId, "정서윤");

    assertThat(service.maskedNames(List.of(memberId, memberId)))
        .containsOnly(entry(memberId, Optional.of("정*윤")));
  }

  @Test
  void 여러_명_중_없는_회원이_있으면_NOT_FOUND() {
    service.seed(memberId, "정서윤");

    assertThatThrownBy(() -> service.maskedNames(List.of(memberId, UUID.randomUUID())))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }
}
