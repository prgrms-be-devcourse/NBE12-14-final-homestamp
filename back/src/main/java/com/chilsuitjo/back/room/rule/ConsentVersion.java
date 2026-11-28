package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;

/**
 * 기록 공유 동의 문구 판(06 CONS-2, 03 6-2). 초대·연결 요청이 보낸 판을 행동 {@code SHARE_CONSENT}의 payload {@code
 * consentVersion}으로 남긴다(06 CONS-2 "payload에 문구 판", 09 3-1 초대 예시).
 *
 * <p>여기서는 요청 본문만 본다: 비어 있지 않고 20자 이하인지(decisions 2026-11-25). 지금 유효한 판인지는 서비스가 {@code
 * TermsVersions.requireCurrentShareConsent}를 pipeline의 처음 실행 검사({@code ActionBatch.firstRunCheck})로
 * 붙여 본다(CONS-4, decisions 2026-11-27, #332). 같은 키의 재생은 그사이 판이 바뀌어도 처음 결과를 돌려준다.
 */
public final class ConsentVersion {

  /** 가입 확인 항목 판과 같은 길이 한도(05 2-1 {@code member_acknowledgement.text_version varchar(20)}). */
  public static final int MAX_LENGTH = 20;

  private ConsentVersion() {}

  /**
   * @throws BusinessException {@code CONSENT_REQUIRED}(판이 없거나 비었음, 06 CONS-2b), {@code
   *     VALIDATION_FAILED}(20자를 넘음)
   */
  public static String require(String version) {
    if (version == null || version.isBlank()) {
      throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
    }
    if (version.length() > MAX_LENGTH) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return version;
  }
}
