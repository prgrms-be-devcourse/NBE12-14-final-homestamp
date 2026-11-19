package com.chilsuitjo.back.auth.repository;

import com.chilsuitjo.back.auth.entity.PhoneVerification;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, UUID> {

  /** 번호 잠금(05): 트랜잭션이 끝날 때 풀린다. 같은 번호의 인증 요청은 목적과 상관없이 차례로 처리된다. */
  @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
  Integer lockPhone(@Param("key") long key);

  /** 같은 진행의 인증 전 행을 잠근다. 인증번호를 확인하는 요청이 그 행을 잡고 있으면 여기서 기다리므로, 발급은 이 잠금까지 얻은 뒤 시각을 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT v FROM PhoneVerification v"
          + " WHERE v.phone = :phone AND v.purpose = :purpose AND v.verifiedAt IS NULL")
  List<PhoneVerification> lockPending(
      @Param("phone") String phone, @Param("purpose") VerificationPurpose purpose);

  /** 다시 받기 간격은 같은 번호·목적의 마지막 행으로 센다(05). */
  Optional<PhoneVerification> findFirstByPhoneAndPurposeOrderByCreatedAtDesc(
      String phone, VerificationPurpose purpose);

  /** 하루 인증 횟수: 같은 번호의 오늘 행 수, 목적 구분 없음(05, 06 ACC-1e·1h). */
  long countByPhoneAndCreatedAtGreaterThanEqual(String phone, Instant startOfDay);

  /** 같은 진행의 인증 전 이전 행을 지금 만료시킨다(06 ACC-1g). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE PhoneVerification v
         SET v.expiresAt = :now, v.version = v.version + 1
       WHERE v.phone = :phone AND v.purpose = :purpose
         AND v.verifiedAt IS NULL AND v.expiresAt > :now
      """)
  int expirePending(
      @Param("phone") String phone,
      @Param("purpose") VerificationPurpose purpose,
      @Param("now") Instant now);

  /** 인증번호를 확인하는 동안 틀린 횟수가 겹쳐 세지지 않게 그 행을 잠근다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT v FROM PhoneVerification v WHERE v.id = :id")
  Optional<PhoneVerification> findForUpdate(@Param("id") UUID id);

  /** 이 행보다 나중에 만든 같은 진행의 행이 있으면 바뀐 행이다(05 {@code CODE_REPLACED}). */
  boolean existsByPhoneAndPurposeAndCreatedAtAfter(
      String phone, VerificationPurpose purpose, Instant createdAt);

  /** 같은 진행의 이전 행(인증 전). 넣은 번호가 바뀐 이전 번호인지 볼 때 쓴다. */
  List<PhoneVerification> findByPhoneAndPurposeAndCreatedAtBeforeAndVerifiedAtIsNull(
      String phone, VerificationPurpose purpose, Instant createdAt);

  /**
   * 인증 표를 쓴 것으로 바꾼다(05: {@code consumed_at}이 비어 있을 때만 채우는 한 줄 갱신). 목적·인증·유효 시간도 같은 문장에서 본다. 0이면 쓸 수
   * 없는 표다.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE PhoneVerification v
         SET v.consumedAt = :now, v.version = v.version + 1
       WHERE v.id = :id AND v.purpose = :purpose
         AND v.verifiedAt IS NOT NULL AND v.expiresAt > :now AND v.consumedAt IS NULL
      """)
  int consume(
      @Param("id") UUID id,
      @Param("purpose") VerificationPurpose purpose,
      @Param("now") Instant now);

  /**
   * 서비스 전체 하루 문자 한도(05 {@code rate_counter}, 키 {@code sms-total}). 인증 문자는 이 한 줄만 센다. 한도 미만일 때만 +1을
   * 한 문장으로 해서, 번호가 달라 잠금이 겹치지 않아도 한도를 넘지 않는다. 0이면 한도에 닿았다. 발송이 실패해도 되돌리지 않는다.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO state.rate_counter AS c (counter_key, window_date, count)
          VALUES ('sms-total', :day, 1)
          ON CONFLICT (counter_key, window_date)
          DO UPDATE SET count = c.count + 1 WHERE c.count < :limit
          """,
      nativeQuery = true)
  int countSmsTotal(@Param("day") LocalDate day, @Param("limit") int limit);
}
