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
   * 해시로 찾고 그 행을 잠근다({@code SELECT … FOR UPDATE}). 같은 토큰으로 동시에 온 재발급은 여기서 줄을 서서, 먼저 온 요청만 교체하고 뒤의
   * 요청은 교체된 뒤의 행을 본다(06 ACC-2b).
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
