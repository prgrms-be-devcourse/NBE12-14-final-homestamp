package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

  /** 작업자가 결과를 적을 때. 통지는 작업자도 바꾸는 행이라 version 대신 행 잠금으로 막는다(04 "동시에 누름"). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM Notice n WHERE n.id = :id")
  Optional<Notice> findForUpdate(@Param("id") UUID id);

  /** 응답 요청에 걸린 통지의 호실. 잠그기 전에 읽어 호실을 먼저 잠그려고 쓴다(호실 → 통지 → 요청, 09 3장 "잠금"). 칸은 만든 뒤 바뀌지 않는다. */
  @Query("SELECT n.roomRecordId FROM Notice n WHERE n.id = :id AND n.responseRequestId IS NOT NULL")
  Optional<UUID> findRequestRoomId(@Param("id") UUID id);

  /**
   * 이 호실의 응답 요청들에 걸린 통지 중 종류가 {@code kinds}인 것(만든 순서, 같으면 ID). 호실도 함께 걸러 다른 호실의 통지는 들어가지 않는다. 잠그지
   * 않는다(읽기 전용 조회).
   */
  @Query(
      """
      SELECT n FROM Notice n
      WHERE n.roomRecordId = :roomRecordId AND n.responseRequestId IN :requestIds
        AND n.kind IN :kinds
      ORDER BY n.createdAt, n.id
      """)
  List<Notice> findForRequests(
      @Param("roomRecordId") UUID roomRecordId,
      @Param("requestIds") Collection<UUID> requestIds,
      @Param("kinds") Collection<NoticeKind> kinds);

  /**
   * 전달 결과 조회를 시작할 통지 여러 건을 ID 순서로 잠근다. 여러 통지를 잠그는 다른 곳({@link #lockByLinks}, 문자 취소)과 같은 순서라 서로 상대가
   * 잡은 행을 기다리며 멈추지 않는다. 순서는 DB의 {@code uuid} 정렬이라 Java에서 정렬하지 않는다({@link UUID#compareTo}와 다를 수 있음).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM Notice n WHERE n.id IN :ids ORDER BY n.id")
  List<Notice> lockAllInIdOrder(@Param("ids") Collection<UUID> ids);

  /**
   * 전달 결과를 물을 시각이 된 보낸 문자를 잠가 가져온다(V1 {@code ix_notice_poll}). {@code SKIP LOCKED}라서 작업자 둘이 같은 통지를
   * 가져가지 않는다({@link OutboxRepository#lockDueSms}와 같은 방식). 트랜잭션 안에서만 부른다.
   *
   * <p>전달 확인 기한({@code delivery_check_due_at})이 없는 줄은 가져가지 않는다. 기한은 보낼 때 정해 저장하는 값이라 없으면 결과 없음을 언제
   * 실패로 볼지 알 수 없다. 지금 설정으로 기한을 지어내거나 실패로 끝내지 않고 그대로 둔다(기한 칸이 생기기 전에 보냈거나 이전 버전 앱이 적은 줄, {@link
   * #countSentWithoutDeliveryCheckDue}로 센다).
   */
  @Query(
      value =
          """
          SELECT * FROM state.notice
          WHERE status = 'SENT' AND next_poll_at <= :now AND delivery_check_due_at IS NOT NULL
          ORDER BY next_poll_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Notice> lockDuePolls(@Param("now") Instant now, @Param("limit") int limit);

  /** 전달 확인 기한 없이 보낸(SENT) 상태인 통지 수. 전달 결과 작업자가 가져가지 않는 줄이라 운영이 알 수 있게 켜질 때 센다. */
  @Query(
      value =
          "SELECT count(*) FROM state.notice WHERE status = 'SENT' AND delivery_check_due_at IS NULL",
      nativeQuery = true)
  long countSentWithoutDeliveryCheckDue();

  /**
   * 이 링크로 보낸 문자 통지 가운데 상태가 {@code status}이고 {@code at} 이전에 보낸 것의 ID(ID 순서). 잠그지 않는다(링크 열람의 최근 열람 예외
   * 판단, {@code NoticeWaitStart#unviewedSent}).
   */
  @Query(
      """
      SELECT n.id FROM Notice n
      WHERE n.roomRecordId = :roomRecordId AND n.linkId = :linkId AND n.channel = :channel
        AND n.status = :status AND n.sentAt <= :at
      ORDER BY n.id
      """)
  List<UUID> findSentIdsByLink(
      @Param("roomRecordId") UUID roomRecordId,
      @Param("linkId") UUID linkId,
      @Param("channel") NoticeChannel channel,
      @Param("status") NoticeStatus status,
      @Param("at") Instant at);

  /**
   * 이 호실에서 넘겨받은 링크를 가리키는 문자 통지를 모두 잠근다(#100 U2 취소). 상태로 거르지 않는다: 문자 줄이 {@code PENDING}인데 통지가 {@code
   * REQUESTED}가 아닌 이상한 조합도 찾아야 해서다. 작업자가 결과를 적을 때와 같이 통지를 문자 줄보다 먼저 잠그고, 여러 행은 ID 순서로 잠근다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT n FROM Notice n
      WHERE n.roomRecordId = :roomRecordId AND n.linkId IN :linkIds AND n.channel = :channel
      ORDER BY n.id
      """)
  List<Notice> lockByLinks(
      @Param("roomRecordId") UUID roomRecordId,
      @Param("linkIds") Collection<UUID> linkIds,
      @Param("channel") NoticeChannel channel);

  /**
   * 이 요청에 걸린 통지 중 상태가 {@code statuses}인 것의 링크 ID(아직 닿지 않은 통지가 쓸 수 있는 문자인지 볼 때). 링크가 없는 통지는 {@code
   * null}로 들어간다.
   */
  @Query(
      "select n.linkId from Notice n where n.responseRequestId = :requestId"
          + " and n.status in :statuses")
  List<UUID> findLinkIdsByRequestAndStatusIn(
      @Param("requestId") UUID responseRequestId,
      @Param("statuses") Collection<NoticeStatus> statuses);

  /**
   * 지금 응답 기회에 재통지가 있나(상태 무관). 재통지는 응답 기회 하나에 한 번이다(03 1-4 "기한"). 재통지 뒤에 같은 요청의 요청·초대 통지가 새로 만들어졌으면
   * 그 재통지는 번호를 고치기 전 기회의 것이라 세지 않는다(#370 T-2). 기다림이 시작된 뒤 요청·초대 통지를 새로 만드는 곳은 번호 고치기로 기다림을 처음으로 되돌린
   * 경우뿐이다({@code InviteRequestLinker}). 두 시각 모두 서버가 통지를 만든 시각이라 업체 시각과 섞이지 않는다. <b>이 가정이 깨지면 여기를
   * 고친다</b>: 시작된 기다림에 요청·초대 통지를 새로 만드는 기능(예: 03 1-1 "다시 보내기")이 생기면 그 통지가 재통지를 옛 기회로 만들어 재통지가 한 번 더
   * 열린다. 같은 시각이면 지금 기회로 센다(빠뜨리는 쪽). {@link ResponseRequestRepository#findRenotifyDue}와 같은 기준이다.
   */
  @Query(
      """
      SELECT count(n) > 0 FROM Notice n
      WHERE n.responseRequestId = :requestId AND n.kind = :renotify
        AND NOT EXISTS (
          SELECT 1 FROM Notice m
          WHERE m.responseRequestId = n.responseRequestId AND m.kind IN :evidence
            AND m.createdAt > n.createdAt)
      """)
  boolean existsCurrentRenotify(
      @Param("requestId") UUID responseRequestId,
      @Param("renotify") NoticeKind renotify,
      @Param("evidence") Collection<NoticeKind> evidence);

  /** 이 요청의 이 종류 통지 수(상태 무관). 재통지 행동 키의 회차를 정한다. */
  long countByResponseRequestIdAndKind(UUID responseRequestId, NoticeKind kind);

  /**
   * 이 요청에 걸린 통지 중 종류가 {@code kinds}, 채널이 {@code channel}, 상태가 {@code statuses}인 것(만든 순서 거꾸로, 같으면 ID
   * 거꾸로). 재통지가 갈 자리와 링크를 고를 때 쓴다. 잠그지 않는다(호실을 잠근 뒤 부름).
   */
  @Query(
      """
      SELECT n FROM Notice n
      WHERE n.responseRequestId = :requestId AND n.kind IN :kinds AND n.channel = :channel
        AND n.status IN :statuses
      ORDER BY n.createdAt DESC, n.id DESC
      """)
  List<Notice> findByRequest(
      @Param("requestId") UUID responseRequestId,
      @Param("kinds") Collection<NoticeKind> kinds,
      @Param("channel") NoticeChannel channel,
      @Param("statuses") Collection<NoticeStatus> statuses);
}
