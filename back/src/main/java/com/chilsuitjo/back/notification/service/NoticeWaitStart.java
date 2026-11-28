package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * 않는다. 정산 초대({@code SETTLEMENT_INVITE})에는 요청을 걸지 않는다. 부르는 곳은 문자 전달 결과와 링크 열람({@link
 * #linkViewed})이다(빠른 쪽이 시작점, 03 1-1 "타이머 시작점"·06 NOTI-3b). 회원의 알림 열람은 아직 없다.
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
   * 링크 열람 기록({@code LINK_VIEW})이 새로 남는 같은 트랜잭션에서 부른다(06 NOTI-3b, 05 4장 "집주인 링크 열람 →
   * notice(VIEWED)"). 그 링크를 담아 <b>이미 보낸</b> 문자 통지(SENT·DELIVERED)를 열람으로 바꾸고({@link
   * Notice#markViewed}), 각각 {@link #reached}로 걸린 요청의 기다림을 시작한다. 아직 보내지 않았거나 취소·최종 실패한 통지는 바꾸지 않는다(그
   * 문자가 닿았다는 증거가 아님).
   *
   * <p>부르는 쪽이 이미 호실 행과 링크 행을 잠갔고, 링크가 지금 유효하며 열 수 있다는 것을 확인한 뒤여야 한다(링크 열람 행동의 {@code save}, {@code
   * LinkViewStateStore}). 그래서 무효(번호 고치기 등)가 된 옛 링크나 실패한 열람으로는 여기까지 오지 않는다. 통지는 다른 곳({@code
   * lockByLinks}·전달 결과 작업자)과 같은 ID 순서로 잠근다(호실 → 링크 → 통지 → 요청). 이 트랜잭션이 되돌아가면 열람 표시와 기다림 시작도 함께
   * 되돌아간다.
   *
   * @param at 이 열람 행동의 기록 시각({@code LINK_VIEW.created_at}). 보낸 시각이 이보다 늦은 통지는 이 열람으로 바꾸지 않는다
   * @return 열람으로 바꾼 통지 수
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int linkViewed(UUID roomRecordId, UUID linkId, Instant at) {
    return linkViewed(roomRecordId, linkId, at, null);
  }

  /**
   * {@link #linkViewed(UUID, UUID, Instant)}와 같고, {@code required}가 있으면 그 통지를 이번에 실제로 열람으로 바꿨는지
   * 확인한다. 바꾸지 못했으면(그사이 처리됐거나, 열람 시각이 그 통지의 보낸 시각보다 앞섬: 서버 시계가 뒤로 감) {@link EvidenceNotRecorded}를 던져
   * 부른 쪽 트랜잭션을 되돌린다. 링크 열람이 그 통지의 증거 키로 행동을 남길 때 쓴다: 키만 쓰이고 증거가 남지 않으면 같은 키의 재생이 그 통지를 다시 보지 않기
   * 때문이다.
   *
   * @param required 이번 열람이 반드시 증거를 남겨야 하는 통지. 없으면 null
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int linkViewed(UUID roomRecordId, UUID linkId, Instant at, UUID required) {
    int viewed = 0;
    boolean requiredViewed = required == null;
    for (Notice notice : notices.lockByLinks(roomRecordId, List.of(linkId), NoticeChannel.SMS)) {
      // 열람 뒤에 보낸 문자는 이 열람의 증거가 아니다(그 문자를 받기 전에 이미 링크를 가지고 있었다). 시각을 끌어올려 맞추지 않는다
      if (notice.getSentAt() != null && notice.getSentAt().isAfter(at)) {
        continue;
      }
      if (notice.markViewed(at)) {
        viewed++;
        reached(notice, at);
        requiredViewed |= notice.getId().equals(required);
      }
    }
    if (!requiredViewed) {
      throw new EvidenceNotRecorded();
    }
    return viewed;
  }

  /** 반드시 증거를 남겨야 하는 통지를 이번 열람으로 바꾸지 못했다. 부른 쪽 트랜잭션이 되돌아가 아무것도 남지 않는다. */
  public static final class EvidenceNotRecorded extends RuntimeException {
    public EvidenceNotRecorded() {
      super("링크 열람이 증거 통지를 열람으로 바꾸지 못함", null, false, false);
    }
  }

  /**
   * 이 링크로 보냈는데 아직 전달 증거가 없는 문자 통지({@code SENT}, 보낸 시각 ≤ {@code at})의 ID(ID 순서). 링크 열람이 최근 열람 안이라도 새
   * 행동을 남길지 본다(06 LINK-1a 예외, decisions 2026-11-28). 업체가 전달을 알린 {@code DELIVERED}와 이미 열람인 {@code
   * VIEWED}는 새 증거가 필요 없어 넣지 않는다. 마지막 열람 뒤에 보낸 것만이 아니라, 앞 열람 때 아직 보냄으로 적히지 않아(작업자가 결과를 늦게 적음) 처리되지
   * 않은 통지도 들어간다. 잠그지 않는 읽기라 부르는 쪽이 잠근 뒤 다시 부르고, 실제 변경은 {@link #linkViewed}가 통지를 잠그고 한다.
   */
  public List<UUID> unviewedSent(UUID roomRecordId, UUID linkId, Instant at) {
    return notices.findSentIdsByLink(
        roomRecordId, linkId, NoticeChannel.SMS, NoticeStatus.SENT, at);
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
