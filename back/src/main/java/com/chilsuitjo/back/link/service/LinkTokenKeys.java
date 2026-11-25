package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.link.rule.LinkToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 링크 ID로 초대 링크({@code INVITE}) 토큰을 만든다(#163, 03 6-1 "링크 토큰"): {@code token =
 * base64url(HMAC-SHA256(키, "homestamp-link:v1:" + linkId))}(256비트, 43자). 지금 부르는 곳은 {@link
 * InviteLinkService}뿐이다. 정산 초대 링크(LINK-3)의 토큰 만드는 법은 그 발급을 만들 때 정한다.
 *
 * <ul>
 *   <li>같은 키와 같은 링크면 언제나 같은 토큰이라, 원래 토큰을 어디에도 저장하지 않아도 문자를 보낼 때 다시 만들 수 있다. DB에는 {@link
 *       LinkToken#hash}만 둔다. 열람은 지금처럼 해시로 찾는다(#89)
 *   <li>DB만 새어도 키 없이는 토큰을 만들 수 없다. 출력이 256비트라 해시에 키를 다시 섞지 않는다
 *   <li>지금 만드는 링크는 모두 키 버전 1이다. 키 버전 칸({@code link.token_key_version})과 키 교체·폐기는 다음 PR(#163 ③)에서
 *       넣고, 그때 기존 행을 1로 채운다
 * </ul>
 *
 * <p>키({@code app.keys.link-token})가 없거나 32바이트보다 짧거나 다른 비밀 키와 같으면 앱이 시작하지 않는다(운영은 Parameter Store에서
 * 받는다). 토큰은 로그에 남기지 않는다(06 LINK-2b).
 */
@Component
class LinkTokenKeys {

  static final String KEY = "app.keys.link-token";

  private static final String HMAC = "HmacSHA256";
  private static final String PREFIX = "homestamp-link:v1:";
  private static final int MIN_KEY_BYTES = 32;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final SecretKeySpec key;

  @Autowired
  LinkTokenKeys(
      @Value("${" + KEY + ":}") String linkKey,
      @Value("${app.keys.verify-code:}") String verifyCode,
      @Value("${app.keys.verify-ticket:}") String verifyTicket,
      @Value("${app.keys.phone-hmac:}") String phoneHmac,
      @Value("${app.keys.upload-ticket:}") String uploadTicket,
      @Value("${app.keys.access-token:}") String accessToken,
      @Value("${app.keys.refresh-token:}") String refreshToken) {
    this(
        linkKey,
        Map.of(
            "app.keys.verify-code", verifyCode,
            "app.keys.verify-ticket", verifyTicket,
            "app.keys.phone-hmac", phoneHmac,
            "app.keys.upload-ticket", uploadTicket,
            "app.keys.access-token", accessToken,
            "app.keys.refresh-token", refreshToken));
  }

  LinkTokenKeys(String linkKey, Map<String, String> otherKeys) {
    if (linkKey == null || linkKey.isBlank()) {
      throw new IllegalStateException(KEY + " 비밀 키가 없다. local·test가 아니면 설정으로 넣어야 시작한다");
    }
    byte[] bytes = linkKey.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(KEY + "는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
    }
    otherKeys.forEach((name, value) -> {
      if (linkKey.equals(value)) {
        throw new IllegalStateException(KEY + "와 " + name + "는 서로 달라야 한다");
      }
    });
    this.key = new SecretKeySpec(bytes, HMAC);
  }

  /** 이 링크의 토큰. 문자 주소에만 넣고 저장·로그에 남기지 않는다. */
  String token(UUID linkId) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return ENCODER.encodeToString(
          mac.doFinal((PREFIX + linkId).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256을 쓸 수 없다", e);
    }
  }

  /** {@code link.token_hash}에 저장할 값. 열람이 찾는 값({@link LinkToken#hash})과 같다. */
  String tokenHash(UUID linkId) {
    return LinkToken.hash(token(linkId));
  }
}
