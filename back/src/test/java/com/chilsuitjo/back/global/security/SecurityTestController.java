package com.chilsuitjo.back.global.security;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 보안 테스트용 주소. 보호된 주소 하나와 06의 공개 주소를 흉내 낸다. */
@RestController
class SecurityTestController {

  @GetMapping("/api/me")
  String me(@LoginMember UUID memberId) {
    return memberId.toString();
  }

  @PostMapping("/api/auth/login")
  String login() {
    return "public";
  }

  @PostMapping("/api/public/links/view")
  String linkView() {
    return "public";
  }

  /** 공개 주소에 {@link LoginMember}를 잘못 붙인 경우. */
  @PostMapping("/api/auth/signup")
  String signupWithLoginMember(@LoginMember UUID memberId) {
    return memberId.toString();
  }
}
