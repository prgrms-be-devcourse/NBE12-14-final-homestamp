package com.chilsuitjo.back.auth.dto;

import java.time.Instant;

/**
 * 인증에 성공하면 주는 인증 표(06 4-1). 가입·비밀번호 재설정·아이디 찾기·번호 변경에 넘긴다.
 *
 * @param ticket 행 ID에 서버 키 서명을 붙인 값
 * @param expiresAt 인증한 때부터 {@code verify.ticket.ttl}(06 ACC-1l)
 */
public record VerificationTicket(String ticket, Instant expiresAt) {

  @Override
  public String toString() {
    return "VerificationTicket[expiresAt=" + expiresAt + "]";
  }
}
