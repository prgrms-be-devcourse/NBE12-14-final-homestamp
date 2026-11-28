package com.chilsuitjo.back.auth.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 휴대폰 인증이 쓰는 비밀 키 세 개(04 비밀값). 인증번호 해시, 인증 표 서명, 번호 HMAC 키를 따로 두고 모두 HMAC-SHA256으로 쓴다.
 *
 * <p>키는 {@code app.keys.*} 설정으로 받는다. local·test만 {@code application.yml}에 고정 값이 있고, 그 밖의 환경에서 값이
 * 없거나 두 키가 같으면 앱이 시작하지 않는다.
 */
@Component
class VerificationKeys {

  private static final String HMAC = "HmacSHA256";
  private static final Pattern PHONE_HMAC = Pattern.compile("[0-9a-f]{64}");
  private static final Base64.Encoder TICKET_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final byte[] codeKey;
  private final byte[] ticketKey;
  private final byte[] phoneKey;

  VerificationKeys(
      @Value("${app.keys.verify-code:}") String codeKey,
      @Value("${app.keys.verify-ticket:}") String ticketKey,
      @Value("${app.keys.phone-hmac:}") String phoneKey) {
    require("app.keys.verify-code", codeKey);
    require("app.keys.verify-ticket", ticketKey);
    require("app.keys.phone-hmac", phoneKey);
    if (codeKey.equals(ticketKey) || codeKey.equals(phoneKey) || ticketKey.equals(phoneKey)) {
      throw new IllegalStateException("app.keys의 비밀 키는 서로 달라야 한다");
    }
    this.codeKey = codeKey.getBytes(StandardCharsets.UTF_8);
    this.ticketKey = ticketKey.getBytes(StandardCharsets.UTF_8);
    this.phoneKey = phoneKey.getBytes(StandardCharsets.UTF_8);
  }

  private static void require(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " 비밀 키가 없다. local·test가 아니면 설정으로 넣어야 시작한다");
    }
  }

  /** 인증번호 해시. 행 ID를 함께 넣어 같은 번호라도 행마다 값이 다르다. */
  String codeHash(UUID verificationId, String code) {
    return HexFormat.of().formatHex(hmac(codeKey, verificationId + ":" + code));
  }

  /** 두 해시가 같은지 시간 차이 없이 비교한다. */
  static boolean sameHash(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 번호 잠금 키(05 {@code pg_advisory_xact_lock}, 번호 HMAC으로 만든 키, 목적과 상관없음). {@link #phoneHmac}와 같은
   * HMAC의 앞 8바이트다. 정규화한 번호를 받는다.
   */
  long phoneLockKey(String phone) {
    return ByteBuffer.wrap(hmac(phoneKey, phone)).getLong();
  }

  /**
   * 저장용 번호 HMAC({@code PhoneHmacs}, decisions 2026-11-26). 번호 HMAC 키로 HMAC-SHA256 한 32바이트 전체를 소문자
   * hex 64자로 낸다. 정규화한 번호를 받는다(정규화는 부르는 쪽, {@link PhoneHmacService}).
   */
  String phoneHmac(String phone) {
    return HexFormat.of().formatHex(hmac(phoneKey, phone));
  }

  /**
   * 저장된 번호 HMAC({@link #phoneHmac} 값)에서 번호 잠금 키를 만든다(#83 Q2). 앞 16자(8바이트)를 부호 있는 long으로 읽어 같은 번호의
   * {@link #phoneLockKey}와 같은 값이다. 원문 번호 없이 가입과 같은 잠금을 잡을 때 쓴다.
   *
   * @throws IllegalArgumentException 소문자 hex 64자가 아님(서버가 저장한 값만 받으므로 사용자 입력 오류가 아니다)
   */
  long phoneLockKeyOfHmac(String phoneHmac) {
    if (phoneHmac == null || !PHONE_HMAC.matcher(phoneHmac).matches()) {
      throw new IllegalArgumentException("번호 HMAC은 소문자 hex 64자여야 한다");
    }
    return HexFormat.fromHexDigitsToLong(phoneHmac, 0, 16);
  }

  /** 인증 표: 행 ID + "." + 행 ID의 서명. 행 ID는 verify 주소에 드러나므로 서명 없이는 표로 쓸 수 없다. */
  String signTicket(UUID verificationId) {
    return verificationId + "." + TICKET_ENCODER.encodeToString(ticketSignature(verificationId));
  }

  /** 서명이 맞으면 행 ID. 모양이 틀리거나 서명이 다르면 비어 있다. */
  Optional<UUID> readTicket(String ticket) {
    if (ticket == null) {
      return Optional.empty();
    }
    int dot = ticket.indexOf('.');
    if (dot < 0) {
      return Optional.empty();
    }
    try {
      UUID id = UUID.fromString(ticket.substring(0, dot));
      byte[] signature = Base64.getUrlDecoder().decode(ticket.substring(dot + 1));
      return MessageDigest.isEqual(signature, ticketSignature(id))
          ? Optional.of(id)
          : Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private byte[] ticketSignature(UUID verificationId) {
    return hmac(ticketKey, "verify-ticket:" + verificationId);
  }

  private static byte[] hmac(byte[] key, String text) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256을 쓸 수 없다", e);
    }
  }
}
