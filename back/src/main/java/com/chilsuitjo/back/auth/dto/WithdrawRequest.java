package com.chilsuitjo.back.auth.dto;

/**
 * 탈퇴({@code POST /api/me/withdraw}, 06 4-1, KEEP-4c). 칸 이름은 06 4-1과 같다(바꾸면 06도 함께).
 *
 * @param password 지금 비밀번호. 규칙은 보지 않고 맞는지만 본다. 없거나 틀리면 {@code PASSWORD_MISMATCH}(KEEP-4c "비밀번호 없이 또는
 *     틀린 비밀번호로")라 입력 검증({@code VALIDATION_FAILED})을 두지 않는다
 */
public record WithdrawRequest(String password) {

  /** 비밀번호가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "WithdrawRequest[***]";
  }
}
