package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V15 회원 번호 HMAC(#462): HMAC은 번호 없이 있을 수 없고, 이 칸을 모르는 이전 앱이 번호만 바꾸면 트리거가 HMAC을 비운다(블루/그린 호환). 실제
 * PostgreSQL에서 본다.
 */
class MemberPhoneHmacMigrationTest extends SchemaTestSupport {

  private static final String HMAC_A = "a".repeat(64);
  private static final String HMAC_B = "b".repeat(64);

  @Test
  void 이전_앱처럼_HMAC_없이_가입한_행은_들어간다() {
    UUID id = insert("01011110001", null);

    assertThat(phoneHmac(id)).isNull();
  }

  @Test
  void 번호_없이_HMAC만_있는_행은_거절한다() {
    assertThatThrownBy(() -> insert(null, HMAC_A))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_member_phone_hmac_needs_phone");
  }

  @Test
  void 이전_앱이_번호만_비우면_HMAC도_비운다() {
    UUID id = insert("01011110002", HMAC_A);

    // 이전 앱의 releasePhone: phone_hmac을 모른다
    jdbc.update("UPDATE member SET phone = NULL, phone_verified_at = NULL WHERE id = ?", id);

    assertThat(phoneHmac(id)).isNull();
  }

  @Test
  void 이전_앱이_번호만_다른_값으로_바꾸면_옛_HMAC을_남기지_않는다() {
    UUID id = insert("01011110003", HMAC_A);

    jdbc.update("UPDATE member SET phone = '01011110004' WHERE id = ?", id);

    assertThat(phoneHmac(id)).isNull();
  }

  @Test
  void 번호와_HMAC을_함께_바꾸면_그대로_둔다() {
    UUID id = insert("01011110005", HMAC_A);

    jdbc.update("UPDATE member SET phone = '01011110006', phone_hmac = ? WHERE id = ?", HMAC_B, id);
    assertThat(phoneHmac(id)).isEqualTo(HMAC_B);

    jdbc.update("UPDATE member SET phone = NULL, phone_hmac = NULL WHERE id = ?", id);
    assertThat(phoneHmac(id)).isNull();
  }

  @Test
  void 번호가_그대로인_UPDATE는_HMAC을_건드리지_않는다() {
    UUID id = insert("01011110007", HMAC_A);

    // 이전 앱의 Hibernate는 모든 칸을 다시 쓴다: 번호는 같은 값
    jdbc.update(
        "UPDATE member SET phone = '01011110007', sms_enabled = false, version = 1 WHERE id = ?",
        id);
    assertThat(phoneHmac(id)).isEqualTo(HMAC_A);

    UUID unfilled = insert("01011110008", null);
    jdbc.update("UPDATE member SET phone_hmac = ? WHERE id = ?", HMAC_B, unfilled);
    assertThat(phoneHmac(unfilled)).isEqualTo(HMAC_B);
  }

  @Test
  void 같은_HMAC은_한_회원에만_있다() {
    insert("01011110009", HMAC_A);

    assertThatThrownBy(() -> insert("01011110010", HMAC_A))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_member_phone_hmac");
  }

  private UUID insert(String phone, String phoneHmac) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO member (id, email, password_hash, display_name, phone, phone_hmac,
          phone_verified_at, sms_enabled, status, created_at, version)
        VALUES (?, ?, 'hash', '정서윤', ?, ?, CURRENT_TIMESTAMP, true, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        id + "@example.com",
        phone,
        phoneHmac);
    return id;
  }

  private String phoneHmac(UUID id) {
    return jdbc.queryForObject("SELECT phone_hmac FROM member WHERE id = ?", String.class, id);
  }
}
