package com.chilsuitjo.back.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 인증번호 확인 요청(06 4-1 {@code POST /api/auth/phone-verifications/{id}/verify}). */
public record VerifyCodeRequest(@NotBlank String code) {

  @Override
  public String toString() {
    return "VerifyCodeRequest[]";
  }
}
