package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.dto.Recipient;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link JpaRecipientDirectory}가 #100 합의의 다섯 결과({@code RecipientDirectory.resolve}, decisions
 * 2026-11-26)와 기존 번호 메서드를 실제 DB로 지키는지 본다. test 프로필은 문자 방식이 {@code fake}라 이 구현이 주입된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class JpaRecipientDirectoryTest {

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  @Autowired private RecipientDirectory recipients;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    room = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at,"
            + " version) VALUES (?, ?, '302호', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        room,
        REGION);
  }

  @Test
  void 주입되는_구현은_room_실제_구현이다() {
    assertThat(recipients).isInstanceOf(JpaRecipientDirectory.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONNECTED", "SHARING_STOPPED"})
  void 연결된_회원이_인증_번호가_있으면_VerifiedMember다(String status) {
    String phone = newPhone();
    UUID member = member(phone);
    UUID party = seat("TENANT", member, status, null);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.VerifiedMember(member, phone));
    assertThat(recipients.phoneOfMember(member)).contains(phone);
  }

  @Test
  void 연결된_회원의_인증이_풀렸으면_UnverifiedMember이고_보낼_번호가_없다() {
    UUID member = member(null);
    UUID party = seat("TENANT", member, "CONNECTED", null);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.UnverifiedMember(member));
    assertThat(recipients.phoneOfMember(member)).isEmpty();
  }

  @Test
  void 초대_자리에_번호가_있으면_Invitee다() {
    String phone = newPhone();
    UUID party = seat("LANDLORD", null, "INVITED", phone);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.Invitee(phone));
  }

  @Test
  void 초대_자리의_번호가_지워졌으면_InviteeWithoutPhone이다() {
    UUID party = seat("LANDLORD", null, "INVITED", null);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.InviteeWithoutPhone());
  }

  @ParameterizedTest
  @ValueSource(strings = {"EMPTY", "NUMBER_ONLY", "REJECTED", "WITHDRAWN"})
  void 그_밖의_자리는_번호가_남아_있어도_Unavailable이다(String status) {
    String phone = newPhone();
    UUID party = seat("LANDLORD", null, status, phone);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.Unavailable());
  }

  @Test
  void 없는_자리는_Unavailable이_아니라_예외이고_번호는_남기지_않는다() {
    UUID missing = UUID.randomUUID();

    assertThatThrownBy(() -> recipients.resolve(missing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(missing.toString());
  }

  @Test
  void 탈퇴한_회원은_번호가_없다() {
    UUID gone = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
        gone);

    assertThat(recipients.phoneOfMember(gone)).isEmpty();
  }

  @Test
  void 연결된_자리의_회원이_탈퇴했으면_번호가_없는_회원이다() {
    // 탈퇴하면 자리도 WITHDRAWN이 되지만, 그 전에 읽은 경우에도 지어낸 번호를 주지 않는다
    UUID gone = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
        gone);
    UUID party = seat("TENANT", gone, "CONNECTED", null);

    assertThat(recipients.resolve(party)).isEqualTo(new Recipient.UnverifiedMember(gone));
  }

  @Test
  void 번호가_든_결과는_문자열로_바꿔도_번호가_나오지_않는다() {
    String phone = newPhone();
    UUID party = seat("LANDLORD", null, "INVITED", phone);

    assertThat(recipients.resolve(party).toString()).doesNotContain(phone);
  }

  private UUID seat(String side, UUID memberId, String status, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, connected_at, sharing_stopped_at,
           created_at, version)
        VALUES (?, ?, ?, ?, ?, ?,
          CASE WHEN CAST(? AS varchar) IN ('CONNECTED', 'SHARING_STOPPED') THEN CURRENT_TIMESTAMP END,
          CASE WHEN CAST(? AS varchar) = 'SHARING_STOPPED' THEN CURRENT_TIMESTAMP END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        invitePhone,
        status,
        status,
        status);
    return id;
  }

  private UUID member(String verifiedPhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, phone, phone_verified_at, status, created_at, version)
        VALUES (?, '김하늘', ?, CASE WHEN CAST(? AS varchar) IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
          'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        verifiedPhone,
        verifiedPhone);
    return id;
  }

  /** 다른 테스트와 겹치지 않는 휴대폰 번호(숫자만). */
  private static String newPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
