package com.chilsuitjo.back.member.repository;

import com.chilsuitjo.back.member.entity.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

  /** 이 번호를 인증한 회원({@code ux_member_phone}이라 많아야 하나). */
  Optional<Member> findByPhone(String phone);
}
