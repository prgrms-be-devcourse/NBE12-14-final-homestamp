package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.rule.AcknowledgementKind;
import com.chilsuitjo.back.member.rule.SignupAcknowledgements;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지금 유효한 문구 판(06 0-1 {@code terms.version.*}, CONS-4, decisions 2026-11-27). 문구·옛 판·바뀐 점은 화면의 정적
 * 페이지에 있고, 서버는 판 글자만 안다. 정식 약관 전문이 준비되기 전까지는 모두 {@code v1}이고, 실제 문구가 바뀌면 새 판({@code YYYY-MM-DD})으로
 * 바꾼다.
 *
 * <p>처리방침 판은 여기 두지 않는다. 처리방침은 확인 대상이 아니고 바뀌면 알리기만 하는데(03 6-2), 그 알림은 #116의 공통 계약을 따른다.
 *
 * @param age14 만 14세 확인({@code AGE_14_CONFIRMED})
 * @param terms 이용약관({@code TERMS_AGREED})
 * @param privacyNotice 개인정보 처리 안내({@code PRIVACY_NOTICE_ACK})
 * @param shareConsent 기록 공유 동의(행동 {@code SHARE_CONSENT}의 {@code consentVersion}, 03 6-2)
 */
@Component
public record TermsVersions(
    @Value("${terms.version.age14}") String age14,
    @Value("${terms.version.terms}") String terms,
    @Value("${terms.version.privacy-notice}") String privacyNotice,
    @Value("${terms.version.share-consent}") String shareConsent) {

  /** 판이 비었거나 칸({@code text_version varchar(20)})보다 길면 앱이 시작하지 않는다. */
  public TermsVersions {
    requireValid("terms.version.age14", age14);
    requireValid("terms.version.terms", terms);
    requireValid("terms.version.privacy-notice", privacyNotice);
    requireValid("terms.version.share-consent", shareConsent);
  }

  /** 그 확인 항목의 지금 판. */
  public String current(AcknowledgementKind kind) {
    return switch (kind) {
      case AGE_14_CONFIRMED -> age14;
      case TERMS_AGREED -> terms;
      case PRIVACY_NOTICE_ACK -> privacyNotice;
    };
  }

  /**
   * 확인한 판이 모두 지금 판인지 본다(가입·다시 확인). 화면이 옛 문구를 보여 준 채 보냈으면(배포 사이 등) 다시 불러오게 한다.
   *
   * @throws BusinessException {@code VERSION_CONFLICT}
   */
  public void requireCurrent(Map<AcknowledgementKind, String> textVersions) {
    textVersions.forEach((kind, version) -> {
      if (!current(kind).equals(version)) {
        throw new BusinessException(ErrorCode.VERSION_CONFLICT);
      }
    });
  }

  /**
   * 공유 동의 문구 판이 지금 판인지 본다. 비었는지·길이는 부르는 쪽(초대·연결, #163)이 먼저 본다(decisions 2026-11-25).
   *
   * @throws BusinessException {@code VERSION_CONFLICT}
   */
  public void requireCurrentShareConsent(String consentVersion) {
    if (!shareConsent.equals(consentVersion)) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
  }

  private static void requireValid(String name, String version) {
    Objects.requireNonNull(version, name);
    if (version.isBlank() || version.length() > SignupAcknowledgements.TEXT_VERSION_MAX) {
      throw new IllegalStateException(name + "는 비지 않고 20자 이하여야 한다");
    }
  }
}
