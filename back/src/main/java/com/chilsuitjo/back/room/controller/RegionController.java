package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.room.dto.RegionOptionResponse;
import com.chilsuitjo.back.room.dto.RegionResponse;
import com.chilsuitjo.back.room.service.RegionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 법정동 찾기·단계 선택 API(06 4-3, ROOM-1). 회원만 부른다(보안 설정의 나머지 요청 인증). */
@RestController
@RequiredArgsConstructor
public class RegionController {

  private final RegionService regions;

  /** 동 이름 앞부분으로 법정동 찾기. 고를 수 없는 동은 빼고 코드 순 20개까지. */
  @GetMapping("/api/regions/search")
  public List<RegionResponse> search(@RequestParam String q) {
    return regions.search(q);
  }

  /** 단계 선택: {@code parent}가 없으면 시·도, 2자리면 시·군·구, 5자리면 읍·면·동. */
  @GetMapping("/api/regions")
  public List<RegionOptionResponse> options(@RequestParam(required = false) String parent) {
    return regions.options(parent);
  }
}
