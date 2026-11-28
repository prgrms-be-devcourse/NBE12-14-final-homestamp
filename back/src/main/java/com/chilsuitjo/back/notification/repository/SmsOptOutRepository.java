package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.SmsOptOut;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 수신 거부 목록({@code state.sms_opt_out}) 조회·등록·해제와 해제 감사. 행은 추가·삭제만 하고 UPDATE는 없다(decisions
 * 2026-11-16). 쓰기는 모두 부르는 쪽 트랜잭션 안에서 돈다.
 */
public interface SmsOptOutRepository extends Repository<SmsOptOut, String> {

  boolean existsById(String phoneHmac);

  /**
   * 행이 없을 때만 넣는다(#163 4번, #370 A-3). 넣었으면 1, 이미 있으면 0이다. 같은 번호를 다른 트랜잭션이 넣는 중이면 그 결과를 기다렸다가 정한다 (PK
   * 충돌 확인).
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES (:phoneHmac, :at)
          ON CONFLICT (phone_hmac) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(@Param("phoneHmac") String phoneHmac, @Param("at") Instant at);

  /**
   * 행을 지우고 지운 수(0 또는 1)를 돌려준다. 같은 행을 다른 트랜잭션이 지우는 중이면 그 결과를 기다렸다가 다시 본다(먼저 지운 쪽이 커밋하면 0). 아직 커밋되지
   * 않은 등록 행은 보지 못해 0이다.
   */
  @Modifying(flushAutomatically = true)
  @Query(value = "DELETE FROM state.sms_opt_out WHERE phone_hmac = :phoneHmac", nativeQuery = true)
  int deleteByPhoneHmac(@Param("phoneHmac") String phoneHmac);

  /**
   * 해제 감사 한 줄과 그 {@code AUDIT_WRITE} 아웃박스 한 줄을 한 문장으로 넣는다. 모양은 탈퇴 함수({@code
   * record.purge_member_acknowledgements}, V1)와 같다: 감사 ID는 DB의 {@code uuidv7()}, 아웃박스 키는 {@code
   * audit:<감사 ID>}, payload는 {@code {"deletionLogId"}}. 앱 역할은 {@code log.deletion_log}에 INSERT만 있어
   * {@code RETURNING}을 쓰지 않는다(grants.sql). 넣은 아웃박스 수(1)를 돌려준다.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          WITH audit AS (SELECT pg_catalog.uuidv7() AS id),
          logged AS (
            INSERT INTO log.deletion_log (id, target_kind, target_id, reason, phone_hmac, created_at)
            SELECT id, 'SMS_OPT_OUT', NULL, 'OWNER_VERIFIED', :phoneHmac, :at FROM audit
          )
          INSERT INTO state.outbox
            (id, kind, dedupe_key, payload, status, attempts, next_attempt_at, created_at, version)
          SELECT pg_catalog.uuidv7(), 'AUDIT_WRITE', 'audit:' || id,
                 jsonb_build_object('deletionLogId', id), 'PENDING', 0, :at, :at, 0
          FROM audit
          """,
      nativeQuery = true)
  int insertReleaseAudit(@Param("phoneHmac") String phoneHmac, @Param("at") Instant at);
}
