package com.chilsuitjo.back.member.repository;

import com.chilsuitjo.back.member.entity.Member;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, UUID> {

  /**
   * 대소문자를 가리지 않고 같은 이메일의 회원이 있는지 본다({@code ux_member_email_lower}와 같은 {@code lower(email)},
   * decisions 2026-11-13). 탈퇴한 회원은 이메일이 null이라 걸리지 않는다.
   */
  @Query(
      value = "SELECT EXISTS (SELECT 1 FROM state.member WHERE lower(email) = lower(:email))",
      nativeQuery = true)
  boolean existsByEmailIgnoringCase(@Param("email") String email);

  /**
   * 대소문자를 가리지 않고 이 이메일의 회원을 찾는다(로그인, 06 ACC-2). {@code ux_member_email_lower}라 많아야 하나다. 탈퇴한 회원은
   * 이메일이 null이라 걸리지 않는다.
   */
  @Query(
      value = "SELECT * FROM state.member WHERE lower(email) = lower(:email)",
      nativeQuery = true)
  Optional<Member> findByEmailIgnoringCase(@Param("email") String email);

  /**
   * 활동 중인 회원의 지금 비밀번호 해시를 읽고 그 행을 공유 잠금한다({@code FOR SHARE}, 트랜잭션이 끝날 때 풀림). 로그인이 토큰을 주는 트랜잭션에서
   * 부른다(#262). 비밀번호 변경·재설정의 {@code UPDATE}와는 서로 기다리고, 로그인끼리는 기다리지 않는다. 탈퇴했거나 없으면 비어 있다.
   */
  @Query(
      value =
          "SELECT password_hash FROM state.member WHERE id = :id AND status = 'ACTIVE' FOR SHARE",
      nativeQuery = true)
  Optional<String> lockPasswordHashForShare(@Param("id") UUID id);

  /**
   * 활동 중인 회원이면 그 행을 공유 잠금하고 ID를 돌려준다({@code FOR SHARE}). 탈퇴의 쓰기 잠금({@link #lockById})과 서로 기다린다.
   * 탈퇴했거나 없으면 비어 있다(연결, #424).
   */
  @Query(
      value = "SELECT id FROM state.member WHERE id = :id AND status = 'ACTIVE' FOR SHARE",
      nativeQuery = true)
  Optional<UUID> lockActiveForShare(@Param("id") UUID id);

  /**
   * 회원 행을 쓰기 잠금하고 읽는다({@code FOR UPDATE}, 트랜잭션이 끝날 때 풀림). 같은 회원의 약관 다시 확인이 동시에 와도 확인 기록이 두 줄이 되지 않게
   * 다시 확인 트랜잭션에서 부른다(CONS-4). 탈퇴 확인 기록 삭제 함수({@code record.purge_member_acknowledgements})도 같은 행을
   * 잠근다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM Member m WHERE m.id = :id")
  Optional<Member> lockById(@Param("id") UUID id);

  /**
   * 탈퇴한 회원의 약관·처리 안내·14세 확인 기록을 지우고 지운 개수를 돌려준다(06 KEEP-4f, 05 0장 "탈퇴 전용 삭제 경로"). V1 함수 {@code
   * record.purge_member_acknowledgements}가 회원 행을 잠그고 {@code WITHDRAWN}을 확인한 뒤 확인 기록마다 삭제 기록과 {@code
   * AUDIT_WRITE} 아웃박스를 남긴다. 회원 가림을 {@code flush}한 같은 트랜잭션에서 부른다(실패하면 탈퇴 전체가 되돌아감).
   *
   * @param at 삭제 기록 시각(부르는 서비스의 {@code Clock})
   */
  @Query(value = "SELECT record.purge_member_acknowledgements(:id, :at)", nativeQuery = true)
  int purgeAcknowledgements(@Param("id") UUID id, @Param("at") Instant at);

  /** 이 번호를 인증한 회원({@code ux_member_phone}이라 많아야 하나). */
  Optional<Member> findByPhone(String phone);
}
