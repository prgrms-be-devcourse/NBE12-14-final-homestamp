package com.chilsuitjo.back.member.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.member.dto.MeResponse;
import com.chilsuitjo.back.member.service.MeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 정보 API(06 4-1 {@code /api/me}, ACC-4·6). 로그인한 회원만 부른다. */
@RestController
@RequiredArgsConstructor
public class MeController {

  private final MeService meService;

  /** 200과 내 정보. 로그인하지 않았거나 탈퇴한 회원이면 {@code LOGIN_REQUIRED}(401). */
  @GetMapping("/api/me")
  public MeResponse me(@LoginMember UUID memberId) {
    return meService.me(memberId);
  }
}
