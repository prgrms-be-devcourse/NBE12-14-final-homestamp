package com.chilsuitjo.back.member.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정({@code POST /api/me}, 06 4-1, ACC-4 이름·ACC-6 문자 설정). 칸 이름은 06 4-1·{@link MeResponse}와 같다.
 *
 * <p>보낸 칸만 바꾸고, 빠진 칸(null)은 그대로 둔다. 회원 문자의 [문자 그만 받기](NOTI-8b)는 {@code smsEnabled}만 보낸다.
 *
 * @param displayName 새 이름. 보내면 비어 있지 않고 50자 이하(가입과 같은 규칙, 05 {@code member.display_name})
 * @param smsEnabled 문자 설정. 꺼도 서비스 안 알림은 받는다(03 K)
 */
public record MeUpdateRequest(
    @Size(max = 50) @Pattern(regexp = "(?s).*\\S.*", message = "이름을 넣어 주세요") String displayName,
    Boolean smsEnabled) {

  /**
   * 문자 끄기만 하는 요청인지(이름 없음 + {@code smsEnabled=false}). 바뀐 약관을 확인하기 전에도 받는다(CONS-4, NOTI-8 문자 그만 받기).
   */
  public boolean onlyTurnsSmsOff() {
    return displayName == null && Boolean.FALSE.equals(smsEnabled);
  }
}
