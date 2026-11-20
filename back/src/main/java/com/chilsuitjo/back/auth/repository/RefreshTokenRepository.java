package com.chilsuitjo.back.auth.repository;

import com.chilsuitjo.back.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * 묶음 잠금: 트랜잭션이 끝날 때 풀린다. 재발급·재사용 폐기·로그아웃은 이 잠금을 먼저 잡고 토큰 행을 잠근다(묶음 → 행). 그래서 묶음을 폐기하는 UPDATE는 같은
   * 묶음에서 진행 중인 재발급이 커밋한 뒤에 시작해 새로 만든 토큰까지 본다.
   */
  @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
  Integer lockFamily(@Param("key") long key);

  /** 묶음 잠금을 잡기 전에 묶음 ID만 읽는다. 행은 잠그지 않는다. */
  @Query("SELECT t.familyId FROM RefreshToken t WHERE t.tokenHash = :hash")
  Optional<UUID> findFamilyIdByTokenHash(@Param("hash") String hash);

  /**
   * 해시로 찾고 그 행을 잠근다({@code SELECT … FOR UPDATE}). 묶음 잠금({@link #lockFamily}) 뒤에 부른다. 먼저 온 요청만 교체하고
   * 뒤의 요청은 교체된 뒤의 행을 본다(06 ACC-2b).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :hash")
  Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

  /** 그 회원의 한 묶음에서 아직 폐기되지 않은 토큰을 모두 폐기한다. 바꾼 줄 수를 돌려준다. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE RefreshToken t SET t.revokedAt = :now, t.version = t.version + 1"
          + " WHERE t.memberId = :memberId AND t.familyId = :familyId AND t.revokedAt IS NULL")
  int revokeFamily(
      @Param("memberId") UUID memberId,
      @Param("familyId") UUID familyId,
      @Param("now") Instant now);
}
