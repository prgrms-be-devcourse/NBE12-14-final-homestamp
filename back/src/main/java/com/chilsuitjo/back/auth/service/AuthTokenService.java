package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.entity.RefreshToken;
import com.chilsuitjo.back.auth.repository.RefreshTokenRepository;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 토큰 발급·재발급·로그아웃(06 ACC-2, 04 4-1). 액세스 토큰은 서버에 남기지 않고, 리프레시 토큰은 해시만 {@code refresh_token}에 둔다.
 *
 * <p>재발급 규칙(불변식 12):
 *
 * <ul>
 *   <li>쓸 수 있는 토큰 R이면 다음 토큰 R'로 바꾸고 R은 폐기한다(로테이션).
 *   <li>폐기된 R이 유예 시간({@code auth.refresh.grace}) 안에 다시 오면 이미 발급한 R'를 다시 돌려준다(ACC-2b). R'도 이미 폐기됐으면
 *       묶음은 그대로 두고 {@code LOGIN_REQUIRED}다.
 *   <li>유예가 지났거나 재발급으로 폐기된 것이 아닌 R이 오면 그 묶음을 모두 폐기하고 {@code REFRESH_REUSED}다(ACC-2c).
 *   <li>모르는 토큰, 만료된 토큰은 {@code LOGIN_REQUIRED}다.
 * </ul>
 */
@Service
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class AuthTokenService {

  private final RefreshTokenRepository tokens;
  private final RefreshTokenKeys keys;
  private final AccessTokens accessTokens;
  private final AuthTokenSettings settings;
  private final Clock clock;

  /** 로그인한 회원에게 새 묶음의 토큰을 준다(ACC-2a). 회원 확인은 부르는 쪽이 먼저 한다. */
  @Transactional
  public IssuedTokens issue(UUID memberId) {
    Objects.requireNonNull(memberId);
    Instant now = clock.instant();
    UUID familyId = UUID.randomUUID();
    String refreshToken = keys.newToken();
    RefreshToken saved =
        tokens.save(
            new RefreshToken(
                memberId,
                familyId,
                RefreshTokenKeys.hash(refreshToken),
                now,
                now.plus(settings.refreshTtl())));
    return issued(saved, refreshToken, now);
  }

  /**
   * 리프레시 토큰으로 새 액세스 토큰과 다음 리프레시 토큰을 준다. 같은 토큰으로 동시에 와도 행을 잠가 한 번만 바꾸고, 뒤의 요청은 유예로 같은 R'를 받는다.
   *
   * <p>재사용으로 묶음을 폐기한 것은 {@code REFRESH_REUSED}를 던져도 되돌리지 않는다({@code noRollbackFor}). 이 메서드는 그 밖의
   * 경우에 {@link BusinessException}을 던지기 전에 아무것도 바꾸지 않는다.
   *
   * @throws BusinessException {@code LOGIN_REQUIRED}(없음·모르는 토큰·만료·유예 안인데 R'가 폐기됨), {@code
   *     REFRESH_REUSED}(묶음 폐기)
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public IssuedTokens refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw loginRequired();
    }
    Instant now = clock.instant();
    RefreshToken current =
        tokens
            .findByTokenHashForUpdate(RefreshTokenKeys.hash(refreshToken))
            .orElseThrow(AuthTokenService::loginRequired);
    if (current.isExpiredAt(now)) {
      throw loginRequired();
    }
    if (current.getRevokedAt() == null) {
      return rotate(current, refreshToken, now);
    }
    if (inGrace(current, now)) {
      return reissue(current, refreshToken, now);
    }
    tokens.revokeFamily(current.getMemberId(), current.getFamilyId(), now);
    throw new BusinessException(ErrorCode.REFRESH_REUSED);
  }

  /**
   * 그 기기(액세스 토큰의 묶음)의 리프레시 토큰을 모두 폐기한다. 이미 폐기됐거나 남의 묶음이면 아무것도 바꾸지 않는다. 이미 낸 액세스 토큰은 만료까지 쓸 수 있다(서버에
   * 저장하지 않음, 04 4-1).
   */
  @Transactional
  public void logout(UUID memberId, UUID familyId) {
    tokens.revokeFamily(
        Objects.requireNonNull(memberId), Objects.requireNonNull(familyId), clock.instant());
  }

  private IssuedTokens rotate(RefreshToken current, String refreshToken, Instant now) {
    String nextToken = keys.next(refreshToken);
    RefreshToken next =
        tokens.save(
            new RefreshToken(
                current.getMemberId(),
                current.getFamilyId(),
                RefreshTokenKeys.hash(nextToken),
                now,
                now.plus(settings.refreshTtl())));
    current.replaceWith(next.getId(), now);
    return issued(next, nextToken, now);
  }

  /** 재발급으로 폐기됐고(가리키는 새 행이 있음) 폐기한 때부터 유예 시간을 넘지 않았다. 경계(정확히 유예 시간)는 받는다. */
  private boolean inGrace(RefreshToken current, Instant now) {
    return current.getReplacedById() != null
        && !now.isAfter(current.getRevokedAt().plus(settings.refreshGrace()));
  }

  /** 유예 안에 다시 온 R: 이미 발급한 R'를 다시 만들어 돌려준다. R'가 폐기·만료됐거나 키가 바뀌어 맞지 않으면 다시 로그인해야 한다. */
  private IssuedTokens reissue(RefreshToken current, String refreshToken, Instant now) {
    Optional<RefreshToken> next = tokens.findById(current.getReplacedById());
    if (next.isEmpty() || !next.get().isUsableAt(now)) {
      throw loginRequired();
    }
    String nextToken = keys.next(refreshToken);
    if (!RefreshTokenKeys.sameHash(RefreshTokenKeys.hash(nextToken), next.get().getTokenHash())) {
      throw loginRequired();
    }
    return issued(next.get(), nextToken, now);
  }

  private IssuedTokens issued(RefreshToken row, String refreshToken, Instant now) {
    String accessToken = accessTokens.issue(row.getMemberId(), row.getFamilyId());
    return new IssuedTokens(accessToken, refreshToken, Duration.between(now, row.getExpiresAt()));
  }

  private static BusinessException loginRequired() {
    return new BusinessException(ErrorCode.LOGIN_REQUIRED);
  }
}
