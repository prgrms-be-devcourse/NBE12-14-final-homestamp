package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.PartyPresence;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 당사자 자리 하나의 값. {@code CounterpartReader}가 돌려준다. 번호는 담지 않는다(문자를 보내기 직전에 {@code RecipientDirectory}로
 * 따로 읽음).
 *
 * <p>공유 중단 판단은 자리 하나로 정할 수 없어(내 제한은 상대가 탈퇴하면 풀림) {@link RoomParties}에 둔다.
 *
 * @param partyId 자리가 아직 없으면(초대 전) null
 * @param memberId 연결 전·번호만 있는 자리·탈퇴면 null
 * @param sharingStoppedAt 공유 중이면 null. 탈퇴해도 남아 있을 수 있다(판단은 {@code presence}로)
 * @param smsEnabled 회원의 "응답이 필요한 일을 문자로도 받기"(03 K). 회원이 아니면 false
 * @param optedOut 그 자리 번호가 수신 거부 목록에 있음(03 K "수신 중단")
 * @param maskedName 가린 이름(03 K "이름 가리기", 문자 본문에 씀). 회원이 아니거나 탈퇴면 null
 */
public record PartyFacts(
    UUID partyId,
    UUID memberId,
    PartyPresence presence,
    Instant sharingStoppedAt,
    boolean smsEnabled,
    boolean optedOut,
    String maskedName) {

  public PartyFacts {
    Objects.requireNonNull(presence, "presence");
  }

  /** 상대 자리가 아직 없는 호실(초대 전). */
  public static PartyFacts empty() {
    return new PartyFacts(null, null, PartyPresence.EMPTY, null, false, false, null);
  }

  public boolean withdrawn() {
    return presence == PartyPresence.WITHDRAWN;
  }
}
