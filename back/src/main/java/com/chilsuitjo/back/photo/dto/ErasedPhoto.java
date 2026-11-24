package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.rule.EraseReason;
import java.time.Instant;

/**
 * 지운 사진의 흔적(불변식 16). 링크 응답과 회원 화면이 같이 쓴다(#89).
 *
 * <p>가린 사본은 지운 사진이 아니라서 이 값을 갖지 않는다. 사본은 {@link PhotoCard#redactedFromPhotoId()}로 원본을 가리킨다.
 *
 * @param side 지운 측(= 올린 측, 불변식 16). {@code THIRD_PARTY}면 측을 보이지 않으므로 null
 * @param at 지운 시각
 * @param reason 지운 이유
 */
public record ErasedPhoto(PartySide side, Instant at, EraseReason reason) {
  public ErasedPhoto {
    if (reason == EraseReason.THIRD_PARTY) {
      side = null;
    }
  }
}
