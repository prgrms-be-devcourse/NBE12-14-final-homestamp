package com.chilsuitjo.back.global.security;

import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보안 테스트용 컨트롤러를 담는다. {@code @TestConfiguration} 안의 {@code static} 클래스라 {@code @SpringBootTest}의
 * 스캔에서 빠지고, 필요한 {@code @WebMvcTest}에서만 {@code @Import}로 넣는다. 주소가 06의 실제 API와 같아 실제 컨트롤러와 함께 등록되면
 * 매핑이 겹치기 때문이다.
 */
@TestConfiguration
class SecurityTestControllerConfig {

  /** 보안 테스트용 주소. 보호된 주소 하나와 06의 공개 주소를 흉내 낸다. */
  @RestController
  static class SecurityTestController {

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
}
