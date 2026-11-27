package com.chilsuitjo.back.timeline.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.timeline.dto.RoomTimeline;
import com.chilsuitjo.back.timeline.service.TimelineService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 경과 API(06 4장 {@code GET /api/rooms/{id}/timeline}, REP-11, S-06). */
@RestController
@RequiredArgsConstructor
public class TimelineController {

  private final TimelineService timelines;

  /** 호실 경과(REP-11a). 보는 사람이 볼 수 있는 행동을 서버 시각 순으로 준다. */
  @GetMapping("/api/rooms/{id}/timeline")
  public RoomTimeline timeline(@PathVariable UUID id, @LoginMember UUID memberId) {
    return timelines.timeline(id, memberId);
  }
}
