package com.chilsuitjo.back.member.dto;

import com.chilsuitjo.back.member.rule.SignupAcknowledgements;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 가입 요청(06 4-1 {@code POST /api/auth/signup}). 06에 칸 이름이 없어 이 DTO가 처음 정한다(화면과 맞출 것).
 *
 * <p>여기서는 06이 정한 입력 규칙만 본다. 비밀번호는 8자 이상, 영문·숫자 포함(06 0-1 {@code password.rule}, ACC-1d)이고, BCrypt가
 * 받는 UTF-8 72바이트를 넘으면 안 된다({@link #PASSWORD_MAX_BYTES}). 이메일·이름 길이 상한은 DB 칸 크기(05 {@code member}:
 * 이메일 320, 이름 50)다. 확인 항목은 {@link #acknowledgements()}가 따로 본다(빠지면 {@code VALIDATION_FAILED}가 아니라
 * {@code AGE_CONFIRM_REQUIRED}·{@code TERMS_REQUIRED}라서, 06 ACC-1a·4-1).
 *
 * @param verificationTicket 휴대폰 인증 표(06 4-1). 확인과 소비는 인증 쪽이 맡는다
 */
public record SignupRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotNull
        @Size(min = 8, message = "비밀번호는 8자 이상이에요")
        @Pattern(regexp = "(?s).*[A-Za-z].*", message = "비밀번호에 영문을 넣어 주세요")
        @Pattern(regexp = "(?s).*[0-9].*", message = "비밀번호에 숫자를 넣어 주세요")
        @MaxUtf8Bytes(value = PASSWORD_MAX_BYTES, message = "비밀번호가 너무 길어요")
        String password,
    @NotBlank @Size(max = 50) String displayName,
    @NotBlank String verificationTicket,
    Boolean ageConfirmed,
    String ageTextVersion,
    Boolean termsAgreed,
    String termsTextVersion,
    Boolean privacyNoticeAcknowledged,
    String privacyNoticeTextVersion) {

  /** BCrypt가 해시에 쓰는 최대 길이(UTF-8 바이트). 넘는 비밀번호는 해시하지 못한다. */
  public static final int PASSWORD_MAX_BYTES = 72;

  /**
   * 확인 항목 세 개와 문구 판을 확인한다.
   *
   * @throws com.chilsuitjo.back.global.error.BusinessException {@link
   *     SignupAcknowledgements#confirm}과 같음
   */
  public SignupAcknowledgements acknowledgements() {
    return SignupAcknowledgements.confirm(
        ageConfirmed,
        ageTextVersion,
        termsAgreed,
        termsTextVersion,
        privacyNoticeAcknowledged,
        privacyNoticeTextVersion);
  }

  /** 비밀번호와 인증 표가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "SignupRequest[email=" + email + ", displayName=" + displayName + "]";
  }
}
