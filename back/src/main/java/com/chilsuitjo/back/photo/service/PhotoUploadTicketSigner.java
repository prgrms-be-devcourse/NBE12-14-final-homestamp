package com.chilsuitjo.back.photo.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 사진을 기록에 붙일 때 제출할 업로드 표를 서버 전용 키로 서명한다(04 5-1). */
@Component
class PhotoUploadTicketSigner {

  private static final JwsHeader HEADER =
      JwsHeader.with(MacAlgorithm.HS256).type("upload-ticket+jwt").build();

  private final JwtEncoder encoder;

  PhotoUploadTicketSigner(@Value("${app.keys.upload-ticket:}") String key) {
    byte[] keyBytes = key.getBytes(UTF_8);
    if (key.isBlank() || keyBytes.length < 32) {
      throw new IllegalStateException("app.keys.upload-ticket은 32바이트 이상이어야 해요.");
    }
    encoder =
        NimbusJwtEncoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
            .algorithm(MacAlgorithm.HS256)
            .build();
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
