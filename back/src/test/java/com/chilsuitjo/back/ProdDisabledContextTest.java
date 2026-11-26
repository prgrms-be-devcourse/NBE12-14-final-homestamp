package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.repository.DisabledSmsGateway;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.DisabledRecipientDirectory;
import com.chilsuitjo.back.notification.service.DisabledVerificationSmsSender;
import com.chilsuitjo.back.notification.service.FakeVerificationSmsSender;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import com.chilsuitjo.back.notification.service.VerificationSmsSender;
import com.chilsuitjo.back.room.service.JpaRecipientDirectory;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 솔라피 연동 전의 운영(prod + {@code app.sms.mode=disabled}, yml prod 기본값)은 정상 기동하고, 문자를 보내지 않으며 성공으로 남기지
 * 않는다(decisions 2026-11-25 운영 문자 모드). prod는 운영처럼 가짜 SSM에서 키·화면 주소·사진 버킷을 받는다(다른 출처로 덮으면
 * SsmProdSettingsGuard가 시작을 막는다). DB만 Testcontainers 연결 정보로 바뀐다(운영 jar에는 없음). 사진 버킷은 이름만 쓰고 S3는 부르지
 * 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.outbox.worker.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@ExtendWith(OutputCaptureExtension.class)
class ProdDisabledContextTest {

  @BeforeAll
  static void provideFakeSsmSettings() {
    SsmTestParameterSource.setParameters(SsmTestParameterSource.validParameters());
  }

  @AfterAll
  static void resetFakeSsmSettings() {
    SsmTestParameterSource.reset();
  }

  private static final String PHONE = "01098765432";

  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private OutboxWorker worker;
  @Autowired private TransactionTemplate transaction;
  @Autowired private RecipientDirectory recipients;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM state.phone_verification");
    jdbc.update("DELETE FROM state.rate_counter");
  }

  @Test
  void 문자_부품은_모두_disabled이고_가짜는_없다() {
    assertThat(context.getBean(SmsGateway.class)).isInstanceOf(DisabledSmsGateway.class);
    assertThat(context.getBean(VerificationSmsSender.class))
        .isInstanceOf(DisabledVerificationSmsSender.class);
    assertThat(context.getBean(RecipientDirectory.class))
        .isInstanceOf(DisabledRecipientDirectory.class);
    assertThat(context.getBeanNamesForType(FakeSmsGateway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FakeVerificationSmsSender.class)).isEmpty();
    // 꺼진 문자 방식에서는 실제 번호 조회도 켜지 않는다(decisions 2026-11-25 운영 문자 모드)
    assertThat(context.getBeanNamesForType(JpaRecipientDirectory.class)).isEmpty();
  }

  @Test
  void API_문서를_만들지_않는다() {
    assertThat(context.getBeanNamesForType(OpenApiWebMvcResource.class)).isEmpty();
  }

  @Test
  void 인증_문자는_보내지_않고_SMS_SEND_FAILED로_답하며_인증_행을_남기지_않는다(CapturedOutput output) throws Exception {
    mvc.perform(
            post("/api/auth/phone-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + PHONE + "\",\"purpose\":\"SIGNUP\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SMS_SEND_FAILED"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM state.phone_verification", Long.class))
        .isZero();
    assertThat(output).contains("인증 문자를 보내지 않음").doesNotContain(PHONE);
  }

  @Test
  void 번호_조회는_지어낸_번호를_돌려주지_않는다() {
    UUID any = UUID.randomUUID();
    assertThat(recipients.phoneOfRecipient(any)).isEmpty();
    assertThat(recipients.phoneOfParty(any)).isEmpty();
    assertThat(recipients.phoneOfMember(any)).isEmpty();
  }

  @Test
  void 통지는_성공으로_남지_않고_문자_작업은_다시_보내지_않게_끝난다() {
    UUID room = UUID.randomUUID();
    UUID party = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999997', '테스트', '꺼짐동')"
            + " ON CONFLICT DO NOTHING");
    jdbc.update(
        """
        INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999997', '꺼짐 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    jdbc.update(
        """
        INSERT INTO state.party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room);
    UUID noticeId =
        transaction.execute(status ->
            noticeOutbox.enqueueSms(
                new SmsNoticeCommand(room, party, NoticeKind.REQUEST, null, null)));

    worker.runOnce();
    worker.runOnce();

    Map<String, Object> notice =
        jdbc.queryForMap("SELECT * FROM state.notice WHERE id = ?", noticeId);
    assertThat(notice.get("status")).isEqualTo("REQUESTED");
    assertThat(notice.get("sent_at")).isNull();
    assertThat(notice.get("provider_message_id")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM state.outbox WHERE dedupe_key = ?",
                String.class,
                "sms:" + noticeId))
        .isEqualTo("FAILED");
  }
}
