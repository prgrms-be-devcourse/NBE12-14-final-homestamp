package com.chilsuitjo.back.member.dto;

import com.chilsuitjo.back.member.entity.Member;
import java.util.List;
import java.util.UUID;

/**
 * 내 정보({@code GET /api/me}, 06 4-1, ACC-4·6, CONS-4). 로그인한 본인에게만 주므로 이름·번호를 가리지 않는다. 비밀번호 해시와 버전은
 * 내보내지 않는다.
 *
 * @param phone 인증한 번호(숫자만, 06 4-0 전화번호). 다른 계정이 같은 번호를 인증하면(ACC-3a) null
 * @param smsEnabled 문자 설정(ACC-6). 꺼도 서비스 안 알림은 받는다
 * @param pendingAcknowledgements 다시 확인해야 할 문구(CONS-4). 늘 목록이고, 비어 있지 않으면 화면이 확인 화면을 먼저 띄운다
 */
public record MeResponse(
    UUID id,
    String email,
    String displayName,
    String phone,
    boolean smsEnabled,
    List<PendingAcknowledgement> pendingAcknowledgements) {

  public static MeResponse from(Member member, List<PendingAcknowledgement> pending) {
    return new MeResponse(
        member.getId(),
        member.getEmail(),
        member.getDisplayName(),
        member.getPhone(),
        member.isSmsEnabled(),
        List.copyOf(pending));
  }
}
