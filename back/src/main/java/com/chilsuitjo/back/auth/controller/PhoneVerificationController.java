package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.VerificationTicket;
import com.chilsuitjo.back.auth.dto.VerifyCodeRequest;
import com.chilsuitjo.back.auth.service.PhoneVerificationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴대폰 인증 API(06 4-1). 로그인 없이 부른다({@code SecurityConfig} 공개 POST).
 *
 * <p>인증 문자 보내기({@code POST /api/auth/phone-verifications})는 notification의 인증 문자 바로 보내기(D 계약)가 들어온
 * 뒤에 더한다. 보내지 않은 인증번호를 보낸 것처럼 돌려주지 않으려고 지금은 두지 않는다.
 */
@RestController
@RequestMapping("/api/auth/phone-verifications")
@RequiredArgsConstructor
public class PhoneVerificationController {

  private final PhoneVerificationService verifications;

  /**
   * 인증번호를 확인하고 인증 표를 준다. 오류는 {@code CODE_INVALID}·{@code CODE_EXPIRED}·{@code CODE_REPLACED}(400).
   */
  @PostMapping("/{id}/verify")
  public VerificationTicket verify(
      @PathVariable UUID id, @Valid @RequestBody VerifyCodeRequest request) {
    return verifications.verify(id, request.code());
  }
}
