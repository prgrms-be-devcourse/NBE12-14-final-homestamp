package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 수신 거부 조회(#163 1-3)를 실제 PostgreSQL과 실제 번호 HMAC 구현으로 본다. 번호는 임의라 다른 테스트와 겹치지 않고, 넣은 행은 지운다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class JpaSmsOptOutsTest {

  @Autowired private SmsOptOuts optOuts;
  @Autowired private PhoneHmacs phoneHmacs;
  @Autowired private JdbcTemplate jdbc;

  private final List<String> inserted = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    inserted.forEach(
        hmac -> jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = ?", hmac));
  }

  @Test
  void 운영_빈은_DB를_읽는_구현_하나다() {
    assertThat(AopUtils.getTargetClass(optOuts)).isEqualTo(JpaSmsOptOuts.class);
  }

  @Test
  void 거부_행이_있으면_true이고_형식만_다른_번호도_같다() {
    String phone = randomPhone();
    optOut(phone);

    assertThat(optOuts.isOptedOut(phone)).isTrue();
    assertThat(
            optOuts.isOptedOut(
                phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7)))
        .isTrue();
  }

  @Test
  void 행이_없으면_false다() {
    assertThat(optOuts.isOptedOut(randomPhone())).isFalse();
  }

  @Test
  void 다른_번호의_거부_행으로_true가_되지_않는다() {
    String phone = randomPhone();
    optOut(phone);
    String other =
        phone.substring(0, 10) + (phone.charAt(10) == '9' ? '0' : (char) (phone.charAt(10) + 1));

    assertThat(optOuts.isOptedOut(other)).isFalse();
  }

  @Test
  void 정규화할_수_없는_번호는_false가_아니라_VALIDATION_FAILED다() {
    assertThatThrownBy(() -> optOuts.isOptedOut("02-123-4567"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
        .hasMessage(ErrorCode.VALIDATION_FAILED.name());
  }

  private void optOut(String phone) {
    String hmac = phoneHmacs.phoneHmac(phone);
    jdbc.update("INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES (?, now())", hmac);
    inserted.add(hmac);
  }

  private static String randomPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
