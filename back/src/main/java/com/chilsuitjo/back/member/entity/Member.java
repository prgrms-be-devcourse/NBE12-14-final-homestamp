package com.chilsuitjo.back.member.entity;

import com.chilsuitjo.back.member.dto.LoginCheck;
import com.chilsuitjo.back.member.rule.MemberStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회원({@code state.member}, 05 2-1, C 테이블). 이메일·비밀번호 해시·이름·번호·설정은 신원 칸이라 고치고 지울 수 있다(05 0장 신원 예외).
 *
 * <p>이메일은 받은 그대로 두고 중복은 {@code lower(email)}로 본다({@code ux_member_email_lower}, decisions
 * 2026-11-13). 번호는 인증을 마친 번호이고 한 계정에만 있다({@code ux_member_phone}, 불변식 8). 탈퇴하면 신원 칸을 지우고 행은 남긴다.
 */
@Entity
@Table(schema = "state", name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  /** 아이디. 탈퇴하면 null. */
  @Column(length = 320)
  private String email;

  /** 비밀번호 해시. 밖으로 내보내지 않는다(확인은 {@link #canLogInWith}로). */
  @Getter(AccessLevel.NONE)
  @Column(name = "password_hash")
  private String passwordHash;

  /** 표시용 이름. 탈퇴하면 null. */
  @Column(name = "display_name", length = 50)
  private String displayName;

  /** 인증한 번호(숫자만, 05 0장 정규화). 다른 계정이 인증하면 null. */
  @Column(length = 20)
  private String phone;

  @Column(name = "phone_verified_at")
  private Instant phoneVerifiedAt;

  @Column(name = "sms_enabled", nullable = false)
  private boolean smsEnabled;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private MemberStatus status;

  @Column(name = "withdrawn_at")
  private Instant withdrawnAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /**
   * 가입한 회원. 비밀번호는 해시만 받는다(해시 방식은 부르는 쪽이 정한다). 번호는 인증 표에서 읽은 정규화된 번호다. 문자 설정은 켬이 기본이다(03 K "회원의 문자
   * 설정").
   */
  public Member(
      String email,
      String passwordHash,
      String displayName,
      String phone,
      Instant phoneVerifiedAt,
      Instant createdAt) {
    this.email = Objects.requireNonNull(email);
    this.passwordHash = Objects.requireNonNull(passwordHash);
    this.displayName = Objects.requireNonNull(displayName);
    this.phone = Objects.requireNonNull(phone);
    this.phoneVerifiedAt = Objects.requireNonNull(phoneVerifiedAt);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.smsEnabled = true;
    this.status = MemberStatus.ACTIVE;
  }

  /**
   * 로그인할 수 있는 회원이고 비밀번호가 맞는지 본다(06 ACC-2). 탈퇴했거나 해시가 없으면 맞지 않다. 해시는 밖으로 내보내지 않고 이 메서드로만 확인한다.
   *
   * @param encoder 해시를 만든 것과 같은 인코더({@code PasswordConfig})
   */
  public boolean canLogInWith(String rawPassword, PasswordEncoder encoder) {
    return status == MemberStatus.ACTIVE
        && passwordHash != null
        && encoder.matches(rawPassword, passwordHash);
  }

  /**
   * 표시용 이름을 바꾼다(06 ACC-4). 신원 칸이라 값은 행동 기록에 남기지 않는다(05 0장).
   *
   * @param displayName 입력 규칙은 부르는 쪽({@code MeUpdateRequest})이 먼저 본다
   */
  public void changeDisplayName(String displayName) {
    this.displayName = Objects.requireNonNull(displayName);
  }

  /** 문자 설정을 켜거나 끈다(06 ACC-6, 03 K "회원의 문자 설정"). 번호는 그대로 둔다. */
  public void changeSmsEnabled(boolean smsEnabled) {
    this.smsEnabled = smsEnabled;
  }

  /**
   * {@link #canLogInWith}가 맞으면, 토큰을 주기 전에 비밀번호가 그대로인지 다시 볼 수 있게 확인한 해시를 담아 돌려준다(06 ACC-2, #262).
   * 해시는 {@link LoginCheck} 안에서 비교에만 쓴다.
   */
  public Optional<LoginCheck> checkLogin(String rawPassword, PasswordEncoder encoder) {
    return canLogInWith(rawPassword, encoder)
        ? Optional.of(new LoginCheck(id, passwordHash))
        : Optional.empty();
  }

  /**
   * 다른 계정이 이 번호를 인증해 이 계정의 인증 번호와 인증 시각을 비운다(06 ACC-3a, 05 {@code member}: 이전 계정의 {@code phone}을
   * NULL로). 이미 연결된 호실(당사자 자리)은 건드리지 않는다.
   */
  public void releasePhone() {
    this.phone = null;
    this.phoneVerifiedAt = null;
  }

  /**
   * 비밀번호 해시를 바꾼다(06 ACC-4a 변경, ACC-5b 재설정). 해시만 받는다(해시 방식은 부르는 쪽이 정한다). 탈퇴한 회원은 바꿀 수 없다.
   *
   * @throws IllegalStateException 탈퇴한 회원
   */
  public void changePasswordHash(String passwordHash) {
    if (status != MemberStatus.ACTIVE) {
      throw new IllegalStateException("탈퇴한 회원의 비밀번호는 바꾸지 않는다");
    }
    this.passwordHash = Objects.requireNonNull(passwordHash);
  }
}
