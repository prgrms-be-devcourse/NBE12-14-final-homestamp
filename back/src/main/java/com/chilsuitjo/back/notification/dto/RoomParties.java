package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestOpening;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import java.util.Objects;

/**
 * 호실 단계와 두 자리. {@code mine}은 행동하는 측, {@code counterpart}는 반대 측(decisions 2026-11-19). 단계는 열 수 있는 확인
 * 요청을 정한다(03 K "거주 종료 뒤", #86).
 *
 * <p>공유 중단 제한은 "상대 탈퇴가 앞선다"(03 B, 06 CONS-3p·3q)라서 자리 하나만 보고 정할 수 없다. 그래서 판단을 여기에 둔다. {@code
 * RoomGate.mySharingStopped}와 같은 기준이다.
 */
public record RoomParties(RoomPhase phase, PartyFacts mine, PartyFacts counterpart) {

  public RoomParties {
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(mine, "mine");
    Objects.requireNonNull(counterpart, "counterpart");
  }

  /** 내 공유 중단 제한이 살아 있나. 내가 그만뒀어도 상대가 탈퇴하면 풀린다(06 CONS-3p). */
  public boolean mySharingStoppedApplies() {
    return mine.sharingStoppedAt() != null && !counterpart.withdrawn();
  }

  /** 상대 공유 중단 제한이 살아 있나. 상대가 탈퇴했으면 남은 공유 중단 시각은 보지 않는다(06 CONS-3q). */
  public boolean counterpartSharingStoppedApplies() {
    return counterpart.sharingStoppedAt() != null && !counterpart.withdrawn();
  }

  /** 지금 호실 단계에서 이 종류의 확인 요청을 열 수 있나({@link RequestOpening}). */
  public boolean canOpen(RequestKind kind) {
    return RequestOpening.allowed(phase, kind);
  }
}
