package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.SignupRequest;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.entity.MemberAcknowledgement;
import com.chilsuitjo.back.member.repository.MemberAcknowledgementRepository;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.AcknowledgementKind;
import com.chilsuitjo.back.member.rule.MemberStatus;
import com.chilsuitjo.back.member.service.MemberService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 가입의 회원 쪽 부분(06 ACC-1b·1c·3a)을 롤백되는 트랜잭션 안에서 본다: 회원·확인 기록 엔티티가 V1 표에 맞게 저장되는지, 이메일 중복을 {@code
 * lower(email)}로 보는지, 같은 번호의 이전 계정 번호를 비우는지. 인증 표까지 지나는 흐름은 {@code SignupControllerTest}.
 */
class MemberSignupPersistenceTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");

  @Autowired private MemberRepository members;
  @Autowired private MemberAcknowledgementRepository acknowledgements;
  @Autowired private MemberService memberService;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 회원은_ACTIVE_문자_켬으로_저장되고_이메일은_받은_그대로_남는다() {
    Member saved = save("Seoyun@Example.com", "01012345678");
    entityManager.flush();
    entityManager.clear();

    Member found = members.findById(saved.getId()).orElseThrow();
    assertThat(found.getEmail()).isEqualTo("Seoyun@Example.com");
    assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(found.isSmsEnabled()).isTrue();
    assertThat(found.getPhone()).isEqualTo("01012345678");
    assertThat(found.getWithdrawnAt()).isNull();
    assertThat(found.getVersion()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash FROM member WHERE id = ?", String.class, saved.getId()))
        .isEqualTo("hash");
  }

  @Test
  void ACC_1c_대소문자만_다른_이메일도_EMAIL_TAKEN() {
    save("Seoyun@Example.com", "01012345678");
    entityManager.flush();

    assertThat(members.existsByEmailIgnoringCase("seoyun@example.COM")).isTrue();
    assertThatThrownBy(() -> memberService.requireEmailAvailable("SEOYUN@example.com"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EMAIL_TAKEN));
  }

  @Test
  void 다른_이메일이나_탈퇴해_이메일을_지운_회원은_막지_않는다() {
    save("other@example.com", "01012345678");
    jdbc.update(
        """
        INSERT INTO member (id, email, status, withdrawn_at, created_at, version)
        VALUES (?, NULL, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID());
    entityManager.flush();

    assertThatCode(() -> memberService.requireEmailAvailable("seoyun@example.com"))
        .doesNotThrowAnyException();
  }

  @Test
  void ACC_1b_확인_기록_세_줄이_각각_문구_판과_함께_남는다() {
    SignupRequest request =
        new SignupRequest(
            "seoyun@example.com",
            "abc12345",
            "정서윤",
            "ticket",
            true,
            "2026-11-12",
            true,
            "2026-11-13",
            true,
            "2026-11-14");

    UUID memberId = memberService.register(request, "hash", "01012345678");
    entityManager.flush();
    entityManager.clear();

    Member member = members.findById(memberId).orElseThrow();
    assertThat(member.getPhone()).isEqualTo("01012345678");
    assertThat(member.getPhoneVerifiedAt()).isEqualTo(member.getCreatedAt());
    List<MemberAcknowledgement> rows = acknowledgements.findByMemberId(memberId);
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .extracting(MemberAcknowledgement::getKind, MemberAcknowledgement::getTextVersion)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(AcknowledgementKind.AGE_14_CONFIRMED, "2026-11-12"),
            org.assertj.core.groups.Tuple.tuple(AcknowledgementKind.TERMS_AGREED, "2026-11-13"),
            org.assertj.core.groups.Tuple.tuple(
                AcknowledgementKind.PRIVACY_NOTICE_ACK, "2026-11-14"));
    assertThat(rows)
        .allSatisfy(row -> {
          assertThat(row.getAgreedAt()).isNotNull();
          assertThat(row.getCreatedAt()).isEqualTo(row.getAgreedAt());
        });
  }

  @Test
  void ACC_3a_같은_번호로_가입하면_이전_계정의_번호와_인증_시각이_비워지고_연결된_호실은_그대로다() {
    Member previous = save("previous@example.com", "01012345678");
    entityManager.flush();
    UUID room = room();
    UUID party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        party,
        room,
        previous.getId());

    UUID newMember =
        memberService.register(allConfirmed("new@example.com", "김정호"), "hash", "01012345678");
    entityManager.flush();
    entityManager.clear();

    Member released = members.findById(previous.getId()).orElseThrow();
    assertThat(released.getPhone()).isNull();
    assertThat(released.getPhoneVerifiedAt()).isNull();
    assertThat(released.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(released.getEmail()).isEqualTo("previous@example.com");
    Member registered = members.findById(newMember).orElseThrow();
    assertThat(registered.getPhone()).isEqualTo("01012345678");
    assertThat(registered.getPhoneVerifiedAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT member_id FROM party WHERE id = ?", UUID.class, party))
        .isEqualTo(previous.getId());
    assertThat(jdbc.queryForObject("SELECT status FROM party WHERE id = ?", String.class, party))
        .isEqualTo("CONNECTED");
  }

  @Test
  void 미리_본_검사를_지나도_같은_이메일이_DB에_걸리면_EMAIL_TAKEN이다() {
    save("Seoyun@Example.com", "01011112222");
    entityManager.flush();

    assertThatThrownBy(() ->
            memberService.register(
                allConfirmed("seoyun@example.com", "정서윤"), "hash", "01012345678"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EMAIL_TAKEN));
  }

  /** 확인 항목을 모두 체크한 가입 요청. 비밀번호와 인증 표는 이 테스트에서 쓰지 않는다(해시와 번호를 따로 넘긴다). */
  private static SignupRequest allConfirmed(String email, String displayName) {
    return new SignupRequest(
        email, "abc12345", displayName, "ticket", true, "v1", true, "v1", true, "v1");
  }

  private Member save(String email, String phone) {
    return members.save(new Member(email, "hash", "정서윤", phone, NOW, NOW));
  }
}
