package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.InAppNotification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 서비스 안 알림 저장소. 읽기는 모두 받는 회원으로 거르고, 만료된 줄은 보이지 않는다(삭제는 만료 정리 작업 몫). */
public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {

  /** 회원의 만료 전 알림. 응답 필요가 먼저, 같은 종류 안에서는 새것부터(같으면 ID, 06 4-2 "응답 필요 먼저"). */
  @Query(
      """
      SELECT n FROM InAppNotification n
      WHERE n.memberId = :memberId AND n.expiresAt > :now
      ORDER BY CASE WHEN n.kind = com.chilsuitjo.back.notification.rule.InAppKind.RESPONSE
               THEN 0 ELSE 1 END, n.createdAt DESC, n.id DESC
      """)
  List<InAppNotification> findVisible(@Param("memberId") UUID memberId, @Param("now") Instant now);

  @Query(
      """
      SELECT count(n) FROM InAppNotification n
      WHERE n.memberId = :memberId AND n.expiresAt > :now AND n.readAt IS NULL
      """)
  long countUnread(@Param("memberId") UUID memberId, @Param("now") Instant now);

  /** 이 회원의 만료 전 알림 하나. 남의 알림·없는 알림·만료된 알림은 빈 값이다(구분하지 않음). */
  @Query(
      """
      SELECT n FROM InAppNotification n
      WHERE n.id = :id AND n.memberId = :memberId AND n.expiresAt > :now
      """)
  Optional<InAppNotification> findVisible(
      @Param("id") UUID id, @Param("memberId") UUID memberId, @Param("now") Instant now);

  /**
   * 아직 읽지 않은 이 회원의 만료 전 알림을 읽음으로 적고 바꾼 줄 수를 돌려준다. 조건부 한 번의 UPDATE라 같은 알림을 동시에 두 번 읽어도 처음 시각 하나만 남고
   * 버전 충돌이 나지 않는다(바뀌는 칸이 읽은 시각뿐이라 버전을 올리지 않음).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE InAppNotification n SET n.readAt = :at
      WHERE n.id = :id AND n.memberId = :memberId AND n.expiresAt > :at AND n.readAt IS NULL
      """)
  int markReadIfUnread(
      @Param("id") UUID id, @Param("memberId") UUID memberId, @Param("at") Instant at);

  /**
   * 만료된 알림(만료 시각 ≤ {@code now}, 목록·안 읽은 수·읽음에서 빠지는 기준과 같음)을 오래된 것부터 {@code limit}줄까지 실제로 지우고 지운 줄
   * 수를 돌려준다(04 "만료 데이터 정리"). 다른 작업자가 잠근 줄은 건너뛰어(SKIP LOCKED) 두 앱이 함께 돌아도 서로 기다리거나 같은 줄을 두 번 지우지
   * 않는다. 알림만 지우고 다른 표·행동 기록은 건드리지 않는다(이 표를 가리키는 FK 없음). 만료 정리는 삭제 기록을 남기지 않는다(05 {@code
   * deletion_log}는 탈퇴·수신 거부 해제만).
   */
  @Modifying
  @Query(
      value =
          """
          DELETE FROM state.in_app_notification
          WHERE id IN (
            SELECT id FROM state.in_app_notification
            WHERE expires_at <= :now
            ORDER BY expires_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED)
          """,
      nativeQuery = true)
  int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);

  /**
   * 이 회원이 받은 알림을 모두 지운다(탈퇴, {@code MemberNotifications}). 지운 줄 수. 탈퇴 트랜잭션이 읽어 둔 엔티티(회원 등)를 떼어 내지 않게
   * 영속성 컨텍스트는 비우지 않는다(알림 엔티티는 이 트랜잭션에서 다시 쓰지 않음).
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM InAppNotification n WHERE n.memberId = :memberId")
  int deleteAllOfMember(@Param("memberId") UUID memberId);
}
