package com.chilsuitjo.back.auth.rule;

/**
 * 휴대폰 인증의 목적(05 {@code phone_verification.purpose}, 06 4-1). 같은 인증 진행은 (번호, 목적)이고, 인증 표는 목적이 같은
 * API에만 쓴다.
 */
public enum VerificationPurpose {
  SIGNUP,
  RESET,
  FIND_ID,
  CHANGE;

  /** 바꾸는 일(가입·비밀번호 재설정·번호 변경)은 인증 표를 한 번만 쓴다. 아이디 찾기는 보기만 해서 유효 시간 안에 다시 쓴다(06 ACC-1f). */
  public boolean usedOnce() {
    return this != FIND_ID;
  }
}
