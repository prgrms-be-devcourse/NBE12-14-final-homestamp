package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.NoticeLine;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 보는 측이 보낸 응답 요청 하나와 그 요청의 통지 상태({@code NoticeStatusQuery}, 06 NOTI-4a·4-9 "조회 응답 안").
 *
 * @param requestId 응답 요청 ID
 * @param request 요청의 종류·대상·상태·시각({@link RequestView}와 같은 값)
 * @param line 지금 보일 한 줄. 보일 통지가 없으면 null(통지 없음을 "보냈어요"로 꾸미지 않는다)
 * @param renotified {@code line}이 재통지의 상태면 true("다시 알렸어요 · …")
 * @param history 이 요청의 통지 전부(요청 통지·재통지, 만든 순서). 없으면 빈 목록
 */
public record SentRequest(
    UUID requestId,
    RequestView request,
    NoticeLine line,
    boolean renotified,
    List<NoticeStep> history) {

  public SentRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(request, "request");
    history = List.copyOf(history);
    if (line == null && renotified) {
      throw new IllegalArgumentException("한 줄이 없으면 재통지 표시도 없어요");
    }
  }
}
