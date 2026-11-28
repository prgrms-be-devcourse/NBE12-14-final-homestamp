package com.chilsuitjo.back.notification.rule;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 재통지 서버 행동의 멱등 키(#370 T-2). {@code rn:<요청>:<링크>}이고, UUID는 16바이트를 base64url(채움 없음, 22자)로 줄여
 * 48자다({@code ActionBatch.MAX_KEY_LENGTH} 64자 안, {@code #} 없음). 링크가 없는 통지면 링크 자리가 {@code -}다(27자).
 *
 * <p>응답 기회 = 요청 + 그 요청 통지의 링크다. 같은 초대 회차에서는 기록마다 링크가 하나이고 링크는 만료·재발급 없이 무효 이유로만 끝나며, 번호를 고치면 새 링크가
 * 생긴다. 그래서 같은 기회의 재시도는 같은 키이고, 번호를 고친 뒤의 기회는 다른 키다. 키는 같은 시도의 다시 보내기를 막을 뿐이고, 기회마다 한 번은 잠근 뒤
 * 확인({@code RenotifyStateStore.check})이 지킨다.
 */
public final class RenotifyKey {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private RenotifyKey() {}

  public static String of(UUID requestId, UUID linkId) {
    Objects.requireNonNull(requestId, "requestId");
    return "rn:" + encode(requestId) + ":" + (linkId == null ? "-" : encode(linkId));
  }

  private static String encode(UUID id) {
    return ENCODER.encodeToString(
        ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits())
            .array());
  }
}
