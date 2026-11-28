package com.chilsuitjo.back.member.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.member.dto.AgreementsRequest;
import com.chilsuitjo.back.member.dto.MeResponse;
import com.chilsuitjo.back.member.dto.MeUpdateRequest;
import com.chilsuitjo.back.member.service.MeService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  /**
   * 이름·문자 설정을 바꾸고 200과 바뀐 내 정보. 보낸 칸만 바꾼다. 입력 규칙에 어긋나면 {@code VALIDATION_FAILED}(400), 로그인하지 않았거나
   * 탈퇴한 회원이면 {@code LOGIN_REQUIRED}(401).
   */
  @PostMapping("/api/me")
  public MeResponse update(
      @LoginMember UUID memberId, @Valid @RequestBody MeUpdateRequest request) {
    return meService.update(memberId, request);
  }

  /**
   * 바뀐 약관·확인 항목을 다시 확인하고 200과 내 정보(CONS-4). 이미 그 판으로 확인한 항목은 다시 남기지 않는다. 같은 종류가 두 번이거나 칸이 틀리면
   * {@code VALIDATION_FAILED}(400), 지금 판이 아니면 {@code VERSION_CONFLICT}(409).
   */
  @PostMapping("/api/me/agreements")
  public MeResponse acknowledge(
      @LoginMember UUID memberId, @Valid @RequestBody AgreementsRequest request) {
    return meService.acknowledge(memberId, request);
  }
}
