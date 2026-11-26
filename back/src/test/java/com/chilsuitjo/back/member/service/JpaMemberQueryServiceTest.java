package com.chilsuitjo.back.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 회원 조회 창구(가린 이름, 인증 번호). 실제 {@code state.member}를 읽는다. 테스트마다 되돌린다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JpaMemberQueryServiceTest {

  @Autowired private MemberQueryService service;
  @Autowired private JdbcTemplate jdbc;

  // ---- 가린 이름 ----

  @Test
  void 회원의_이름은_가려서_돌려받는다() {
    UUID member = active("정서윤", "01012345678");

    assertThat(service.maskedName(member)).contains("정*윤");
  }

  @Test
  void 탈퇴한_회원은_이름이_비어_있다() {
    assertThat(service.maskedName(withdrawn())).isEmpty();
  }

  @Test
  void 이름_칸이_비어_있으면_이름이_없다() {
    assertThat(service.maskedName(active(null, null))).isEmpty();
  }

  @Test
  void 여러_회원의_이름을_한_번에_가려서_돌려받는다() {
    UUID seoyun = active("정서윤", null);
    UUID minsu = active("남궁민수", null);
    UUID gone = withdrawn();

    Map<UUID, Optional<String>> names = service.maskedNames(List.of(seoyun, minsu, gone, seoyun));

    assertThat(names)
        .containsExactly(
            entry(seoyun, Optional.of("정*윤")),
            entry(minsu, Optional.of("남**수")),
            entry(gone, Optional.empty()));
  }

  @Test
  void 빈_목록이면_빈_맵() {
    assertThat(service.maskedNames(List.of())).isEmpty();
  }

  @Test
  void 여러_명_중_없는_회원이_있으면_NOT_FOUND() {
    UUID seoyun = active("정서윤", null);

    expectNotFound(() -> service.maskedNames(List.of(seoyun, UUID.randomUUID())));
  }

  // ---- 인증 번호 ----

  @Test
  void 인증한_번호를_돌려받는다() {
    UUID member = active("정서윤", "01012345678");

    assertThat(service.verifiedPhone(member)).contains("01012345678");
  }

  @Test
  void 다른_계정이_번호를_인증해_인증이_풀렸으면_비어_있다() {
    // 06 ACC-3a: 이전 계정의 phone을 NULL로
    assertThat(service.verifiedPhone(active("정서윤", null))).isEmpty();
  }

  @Test
  void 탈퇴한_회원은_번호가_비어_있다() {
    assertThat(service.verifiedPhone(withdrawn())).isEmpty();
  }

  @Test
  void 없는_회원은_NOT_FOUND() {
    expectNotFound(() -> service.maskedName(UUID.randomUUID()));
    expectNotFound(() -> service.verifiedPhone(UUID.randomUUID()));
  }

  private UUID active(String displayName, String phone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, phone, phone_verified_at, status, created_at, version)
        VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        displayName,
        phone,
        phone == null ? null : java.sql.Timestamp.from(java.time.Instant.now()));
    return id;
  }

  /** 탈퇴하면 신원 칸을 지우고 행만 남긴다(05 2-1). */
  private UUID withdrawn() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private static void expectNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
