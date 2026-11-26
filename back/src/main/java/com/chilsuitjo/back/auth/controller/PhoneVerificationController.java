package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.SendCodeRequest;
import com.chilsuitjo.back.auth.dto.VerificationTicket;
import com.chilsuitjo.back.auth.dto.VerifyCodeRequest;
import com.chilsuitjo.back.auth.service.PhoneVerificationSendService;
import com.chilsuitjo.back.auth.service.PhoneVerificationService;
import com.chilsuitjo.back.global.response.CreatedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 휴대폰 인증 API(06 4-1). 로그인 없이 부른다({@code SecurityConfig} 공개 POST). */
@RestController
@RequestMapping("/api/auth/phone-verifications")
@RequiredArgsConstructor
public class PhoneVerificationController {

  private final PhoneVerificationService verifications;
  private final PhoneVerificationSendService sends;

  /**
   * 인증 문자를 보내고 201 + 인증 ID({@code id})를 준다. 원문 인증번호와 번호는 응답에 넣지 않는다. 오류는 {@code RATE_LIMITED}(429,
   * {@code Retry-After})·{@code DAILY_LIMIT}(429)·{@code SMS_SEND_FAILED}(503)·{@code
   * VALIDATION_FAILED}(400).
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedResponse send(@Valid @RequestBody SendCodeRequest request) {
    return new CreatedResponse(sends.send(request.phone(), request.purpose()));
  }

  /**
   * 인증번호를 확인하고 인증 표를 준다. 오류는 {@code CODE_INVALID}·{@code CODE_EXPIRED}·{@code CODE_REPLACED}(400).
   */
  @PostMapping("/{id}/verify")
  public VerificationTicket verify(
      @PathVariable UUID id, @Valid @RequestBody VerifyCodeRequest request) {
    return verifications.verify(id, request.code());
  }
}
