package com.chilsuitjo.back.member.rule;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 가입 확인 항목 세 개와 각 문구 판(06 ACC-1a·1b, CONS-1a, 03 6-2 "가입할 때"). 만들 때 세 항목이 모두 체크됐는지와 판이 비어 있지 않은지를
 * 본다.
 *
 * <p>판이 지금 유효한 판인지는 여기서 보지 않고 {@code MemberService}가 {@code TermsVersions}로 본다(CONS-4).
 */
public record SignupAcknowledgements(Map<AcknowledgementKind, String> textVersions) {

  /** 05 2-1 {@code member_acknowledgement.text_version varchar(20)}. */
  public static final int TEXT_VERSION_MAX = 20;

  public SignupAcknowledgements {
    textVersions = Collections.unmodifiableMap(new EnumMap<>(textVersions));
  }

  /**
   * 체크 여부와 판을 확인한다. 만 14세를 먼저 보고, 그다음 약관·처리 안내를 본다.
   *
   * @throws BusinessException {@code AGE_CONFIRM_REQUIRED}(만 14세 미체크), {@code TERMS_REQUIRED}(약관 또는
   *     처리 안내 미체크), {@code VALIDATION_FAILED}(판이 비었거나 칸보다 김)
   */
  public static SignupAcknowledgements confirm(
      Boolean ageConfirmed,
      String ageTextVersion,
      Boolean termsAgreed,
      String termsTextVersion,
      Boolean privacyNoticeAcknowledged,
      String privacyNoticeTextVersion) {
    if (!Boolean.TRUE.equals(ageConfirmed)) {
      throw new BusinessException(ErrorCode.AGE_CONFIRM_REQUIRED);
    }
    if (!Boolean.TRUE.equals(termsAgreed) || !Boolean.TRUE.equals(privacyNoticeAcknowledged)) {
      throw new BusinessException(ErrorCode.TERMS_REQUIRED);
    }
    Map<AcknowledgementKind, String> versions = new EnumMap<>(AcknowledgementKind.class);
    versions.put(AcknowledgementKind.AGE_14_CONFIRMED, requireVersion(ageTextVersion));
    versions.put(AcknowledgementKind.TERMS_AGREED, requireVersion(termsTextVersion));
    versions.put(AcknowledgementKind.PRIVACY_NOTICE_ACK, requireVersion(privacyNoticeTextVersion));
    return new SignupAcknowledgements(versions);
  }

  private static String requireVersion(String version) {
    if (version == null || version.isBlank() || version.length() > TEXT_VERSION_MAX) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return version;
  }
}
