package com.chilsuitjo.back.photo.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 사진을 기록에 붙일 때 제출할 업로드 표를 서버 전용 키로 서명하고, 붙일 때 서명을 확인한다(04 5-1). */
@Component
class PhotoUploadTicketSigner {

  private static final String TYPE = "upload-ticket+jwt";
  private static final JwsHeader HEADER = JwsHeader.with(MacAlgorithm.HS256).type(TYPE).build();

  private final JwtEncoder encoder;
  private final JwtDecoder decoder;

  /**
   * 서명을 확인한 업로드 표의 내용.
   *
   * @param expiresAt 만료는 여기서 보지 않는다. 붙이는 쪽이 {@code Clock}으로 보고 {@code UPLOAD_TICKET_EXPIRED}로 거절한다
   */
  record Claims(
      UUID roomRecordId,
      UUID memberId,
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      Instant deviceTakenAt,
      Instant expiresAt) {}

  PhotoUploadTicketSigner(@Value("${app.keys.upload-ticket:}") String key) {
    byte[] keyBytes = key.getBytes(UTF_8);
    if (key.isBlank() || keyBytes.length < 32) {
      throw new IllegalStateException("app.keys.upload-ticket은 32바이트 이상이어야 해요.");
    }
    SecretKeySpec secret = new SecretKeySpec(keyBytes, "HmacSHA256");
    encoder = NimbusJwtEncoder.withSecretKey(secret).algorithm(MacAlgorithm.HS256).build();
    NimbusJwtDecoder nimbus =
        NimbusJwtDecoder.withSecretKey(secret)
            .macAlgorithm(MacAlgorithm.HS256)
            .jwtProcessorCustomizer(processor ->
                processor.setJWSTypeVerifier(
                    new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(TYPE))))
            .build();
    // 만료는 붙이는 쪽이 본다: 서명이 틀린 표(INVALID)와 만료된 표(EXPIRED)를 다른 오류로 알려야 한다(06 PHO-1b·1c)
    nimbus.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    decoder = nimbus;
  }

  /** 서명·형식이 맞으면 내용, 아니면 빈 값. 만료는 보지 않는다. */
  Optional<Claims> verify(String ticket) {
    try {
      Jwt jwt = decoder.decode(ticket);
      String deviceTakenAt = jwt.getClaimAsString("deviceTakenAt");
      return Optional.of(
          new Claims(
              UUID.fromString(jwt.getClaimAsString("roomId")),
              UUID.fromString(jwt.getSubject()),
              requireText(jwt.getClaimAsString("originalKey")),
              requireText(jwt.getClaimAsString("thumbKey")),
              requireText(jwt.getClaimAsString("originalSha256")),
              requireText(jwt.getClaimAsString("thumbSha256")),
              deviceTakenAt == null ? null : Instant.parse(deviceTakenAt),
              Objects.requireNonNull(jwt.getExpiresAt())));
    } catch (JwtException
        | IllegalArgumentException
        | NullPointerException
        | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("업로드 표에 칸이 비었어요");
    }
    return value;
  }

  String sign(
      UUID roomId,
      UUID memberId,
      String originalKey,
      String thumbKey,
      UploadFileRequest file,
      Instant expiresAt) {
    var claims =
        JwtClaimsSet.builder()
            .subject(memberId.toString())
            .claim("roomId", roomId.toString())
            .claim("originalKey", originalKey)
            .claim("thumbKey", thumbKey)
            .claim("originalSha256", file.originalSha256())
            .claim("thumbSha256", file.thumbSha256())
            .expiresAt(expiresAt);
    if (file.deviceTakenAt() != null) {
      claims.claim("deviceTakenAt", file.deviceTakenAt().toString());
    }
    return encoder.encode(JwtEncoderParameters.from(HEADER, claims.build())).getTokenValue();
  }
}
