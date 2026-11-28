package com.chilsuitjo.back.link.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 링크 열람({@code LINK_VIEW}) 단계의 저장. 링크 열람에는 상태 칸이 없고, 열람 행동이 처음 남는 같은 트랜잭션에서 전달 증거만 적는다(06 NOTI-3b,
 * 05 4장 "집주인 링크 열람 → notice(VIEWED)"). 행동에 딸린 일을 {@code save}에서 주인 서비스로 넣는 자리다({@link StateStore},
 * 09 3-1). 접근 검증은 잠금과 확인만 하고 쓰지 않는다({@code ActionBatch.accessCheck}).
 *
 * <p>pipeline이 호실 잠금 → 호실 단위 거절 → 링크 잠금·재확인(접근 검증) → 멱등 조회를 마친 뒤에만 {@code save}가 불리므로, 무효 링크·막힌
 * 열람·같은 키의 재생에서는 증거가 남지 않는다. 시각은 이 열람 행동의 기록 시각({@link ActionRequestContext#at})이다. 뒤에서 실패하면 함께
 * 되돌아간다.
 */
@Component
public class LinkViewStateStore implements StateStore<Void> {

  private final NoticeWaitStart waitStart;
  private final ActionRequestContext request;

  public LinkViewStateStore(NoticeWaitStart waitStart, ActionRequestContext request) {
    this.waitStart = waitStart;
    this.request = request;
  }

  /** 이 링크로 보냈는데 아직 전달 증거가 없는 문자 통지 ID({@code NoticeWaitStart#unviewedSent}). 최근 열람 예외 판단에만 쓴다. */
  public List<UUID> unviewedSent(UUID roomRecordId, UUID linkId, Instant at) {
    return waitStart.unviewedSent(roomRecordId, linkId, at);
  }

  @Override
  public Void load(ActionCommand command) {
    return null;
  }

  @Override
  public SavedState save(ActionCommand command, Void next) {
    waitStart.linkViewed(command.roomRecordId(), command.target().id(), request.at());
    return SavedState.unchanged();
  }
}
