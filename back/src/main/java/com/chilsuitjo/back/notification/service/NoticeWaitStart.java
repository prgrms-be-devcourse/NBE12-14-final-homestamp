package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청에 걸린 통지가 상대에게 닿으면 그 응답 요청의 기다림을 시작한다(03 1-1 "타이머 시작점", 05 4장 "전달 결과 조회"). 사용자의 행동이 아니라 서버가 본
 * 사실이라 행동 기록을 남기지 않고 {@code ActionPipeline}·{@code WaitService}를 거치지 않는다(05 4장 표의 그 줄은 A 칸이 비어 있음).
 *
 * <p><b>부르는 법.</b> 통지 결과를 적는 트랜잭션 안에서 부른다. 통지를 잠그기 전에 {@link #lockRoomOf}로 호실을 먼저 잠그고(호실 → 통지 → 요청.
 * 행동과 문자 취소가 호실 → 통지 순서라 교착이 없다, 09 3장 "잠금"), 통지를 닿음으로 바꾼 같은 트랜잭션에서 {@link #reached}를 부른다. 둘 중 하나가
 * 실패하면 함께 되돌아가 통지는 그대로 남고 다음 조회에서 다시 적는다.
 *
 * <p>요청 통지({@code REQUEST})와 요청이 걸린 초대 문자({@code INVITE})만 기다림을 시작한다. 초대 문자에 요청이 걸리는 것은 같은 점검 초대에서
 * 문자 한 통이 초대이자 그 점검의 확인 요청 통지일 때뿐이다({@link InviteRequestLinker}, #370 A-7). 재통지({@code RENOTIFY})가
 * 닿는 것은 미확인 기한(06 0-1 {@code wait.unconfirmed})의 시작이라 기다림 타이머(d3)에서 다루고, 알림({@code INFO})은 답을 묻지
 * 않는다. 정산 초대({@code SETTLEMENT_INVITE})에는 요청을 걸지 않는다. 지금 이것을 부르는 곳은 문자 전달 결과뿐이다. 링크 열람·알림 열람도 같은
 * 메서드를 부르면 된다(빠른 쪽이 시작점).
 *
 * <p><b>고치기 전 번호로 간 통지</b>: 링크가 번호 고치기로 무효({@code REINVITED})가 된 통지가 닿은 것은 시작점이 아니다. 그 문자는 지금 상대가
 * 아닌 옛 번호로 갔다(#370 T-2, #399). 업체의 전달 사실은 통지에 그대로 남고(부르는 쪽이 이미 적음), 요청의 기다림만 시작하거나 앞당기지 않는다.
 * 연결({@code CONNECTED})로 무효가 된 링크는 맞는 사람이 연결한 것이라 그대로 시작점이다. 다른 이유로 무효인 링크는 지금처럼 요청이 열려 있는지로만 본다.
 * 번호를 고치기 <b>전에</b> 이미 닿아 시작된 기다림은 번호 고치기 처리(T-2 (2) "새 전달부터 기한을 다시 셈")가 다룬다.
 */
@Component
public class NoticeWaitStart {

  private final NoticeRepository notices;
  private final ResponseRequestRepository requests;
  private final RoomGateReader rooms;
  private final InviteLinks inviteLinks;
  private final Duration renotifyAfter;

  public NoticeWaitStart(
      NoticeRepository notices,
      ResponseRequestRepository requests,
      RoomGateReader rooms,
      InviteLinks inviteLinks,
      @Value("${app.wait.renotify:48h}") Duration renotifyAfter) {
    this.notices = notices;
    this.requests = requests;
    this.rooms = rooms;
    this.inviteLinks = inviteLinks;
    this.renotifyAfter = WaitDurations.require(renotifyAfter, "app.wait.renotify");
  }

  /** 응답 요청에 걸린 통지면 그 호실 행을 잠근다. 통지를 잠그기 전에 부른다. 걸린 요청이 없으면 아무것도 잠그지 않는다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockRoomOf(UUID noticeId) {
    notices.findRequestRoomId(noticeId).ifPresent(room -> rooms.lockAndRead(room, Actor.server()));
  }

  /**
   * 통지가 {@code at}에 닿았다. 요청 통지가 걸린 열린 요청이면 기다림을 시작한다({@link ResponseRequest#startWaiting}). 끝난 요청이면
   * 버린다. 링크가 번호 고치기로 무효가 된 통지면 버린다(위 "고치기 전 번호로 간 통지"). 통지와 요청의 호실이 같은지는 DB 외래 키({@code
   * fk_notice_response_request_id_room})가 막는다.
   *
   * @return 기다림을 이번에 시작했으면 true
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean reached(Notice notice, Instant at) {
    if (notice.getResponseRequestId() == null
        || (notice.getKind() != NoticeKind.REQUEST && notice.getKind() != NoticeKind.INVITE)) {
      return false;
    }
    if (notice.getLinkId() != null && inviteLinks.isReinvited(notice.getLinkId())) {
      return false; // 옛 번호에 닿은 것이라 지금 상대에게 닿은 증거가 아니다(#370 T-2)
    }
    ResponseRequest request =
        requests
            .findById(notice.getResponseRequestId())
            .orElseThrow(() -> new IllegalStateException("통지에 걸린 응답 요청이 없어요"));
    return request.startWaiting(at, renotifyAfter);
  }
}
