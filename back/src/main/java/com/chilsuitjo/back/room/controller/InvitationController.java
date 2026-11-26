package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.InvitationSummary;
import com.chilsuitjo.back.room.service.InvitationQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 받은 초대(06 4-3, ROOM-5, S-08). 연결·거절({@code connect}·{@code reject})은 #163 후속에서 더한다. */
@RestController
@RequiredArgsConstructor
public class InvitationController {

  private final InvitationQueryService invitations;

  /** 내 인증 번호로 받은 초대. 몇 건뿐이라 커서 없이 배열로 준다(06 4-0 "목록"의 예외). */
  @GetMapping("/api/invitations")
  public List<InvitationSummary> list(@LoginMember UUID memberId) {
    return invitations.list(memberId);
  }
}
