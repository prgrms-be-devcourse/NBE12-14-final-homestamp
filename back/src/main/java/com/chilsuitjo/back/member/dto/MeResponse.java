package com.chilsuitjo.back.member.dto;

import com.chilsuitjo.back.member.entity.Member;
import java.util.UUID;

/**
 * 내 정보({@code GET /api/me}, 06 4-1, ACC-4·6). 로그인한 본인에게만 주므로 이름·번호를 가리지 않는다. 비밀번호 해시와 버전은 내보내지 않는다.
 *
 * <p>06 4-1의 "확인해야 할 새 문구 판"(CONS-4)은 지금 서버가 쓰는 문구 판을 정할 곳이 아직 없어 이 응답에 없다. CONS-4를 만들 때 칸을 더한다(06
 * 4-0 버전: 칸은 더하기만 한다).
 *
 * @param phone 인증한 번호(숫자만, 06 4-0 전화번호). 다른 계정이 같은 번호를 인증하면(ACC-3a) null
 * @param smsEnabled 문자 설정(ACC-6). 꺼도 서비스 안 알림은 받는다
 */
public record MeResponse(
    UUID id, String email, String displayName, String phone, boolean smsEnabled) {

  public static MeResponse from(Member member) {
    return new MeResponse(
        member.getId(),
        member.getEmail(),
        member.getDisplayName(),
        member.getPhone(),
        member.isSmsEnabled());
  }
}
