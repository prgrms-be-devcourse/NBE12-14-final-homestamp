package com.chilsuitjo.back.link.controller;

import com.chilsuitjo.back.link.dto.LinkViewRequest;
import com.chilsuitjo.back.link.dto.LinkViewResponse;
import com.chilsuitjo.back.link.service.LinkViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 전 링크 API(06 4-4). 로그인 없이 열리고(보안 설정의 {@code POST /api/public/**}), 토큰은 본문으로 받아 link가 확인한다. IP별
 * 횟수 제한은 nginx {@code limit_req}가 맡는다(LINK-2c).
 */
@RestController
@RequestMapping("/api/public/links")
@RequiredArgsConstructor
public class PublicLinkController {

  private final LinkViewService linkViewService;

  @PostMapping("/view")
  public LinkViewResponse view(@RequestBody LinkViewRequest request) {
    return linkViewService.view(request);
  }
}
