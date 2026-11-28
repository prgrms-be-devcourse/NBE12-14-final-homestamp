package com.chilsuitjo.back.notification.repository;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 솔라피 API Key 인증 헤더. 형식은 공식 SDK(solapi-nodejs {@code src/lib/authenticator.ts})와 같다: {@code
 * HMAC-SHA256 apiKey=<키>, date=<ISO 8601>, salt=<32자>, signature=<hex(HMAC-SHA256(비밀, date +
 * salt))>}.
 *
 * <p>공식 문서는 방식(HMAC_SHA256)만 적고 헤더 모양·시각 허용 오차를 적지 않았다. 실제 계정으로 한 번 확인할 때까지 이 형식은 SDK 근거다.
 */
final class SolapiAuth {

  private static final String SALT_CHARS =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int SALT_LENGTH = 32;
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.UTC);

  private final SecureRandom random = new SecureRandom();

  String header(String apiKey, String apiSecret, Instant now) {
    return header(apiKey, apiSecret, now, salt());
  }

  static String header(String apiKey, String apiSecret, Instant now, String salt) {
    String date = DATE.format(now.truncatedTo(ChronoUnit.SECONDS));
    return "HMAC-SHA256 apiKey="
        + apiKey
        + ", date="
        + date
        + ", salt="
        + salt
        + ", signature="
        + sign(apiSecret, date + salt);
  }

  static String sign(String apiSecret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("솔라피 서명을 만들 수 없어요", e);
    }
  }

  private String salt() {
    StringBuilder salt = new StringBuilder(SALT_LENGTH);
    for (int i = 0; i < SALT_LENGTH; i++) {
      salt.append(SALT_CHARS.charAt(random.nextInt(SALT_CHARS.length())));
    }
    return salt.toString();
  }
}
