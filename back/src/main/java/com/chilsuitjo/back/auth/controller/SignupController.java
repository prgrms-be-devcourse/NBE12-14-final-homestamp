package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.service.SignupService;
import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.member.dto.SignupRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 API(06 4-1). 로그인 없이 부른다({@code SecurityConfig} 공개 POST). 성공하면 201과 회원 ID만 돌려주고 로그인시키지 않는다(로그인은
 * ACC-2).
 */
@RestController
@RequiredArgsConstructor
public class SignupController {

  private final SignupService signups;

  @PostMapping("/api/auth/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedResponse signUp(@Valid @RequestBody SignupRequest request) {
    return new CreatedResponse(signups.signUp(request));
  }
}
