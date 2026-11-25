package com.chilsuitjo.back.notification.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 통지를 받을 자리가 지금 누구이고 알려진 번호가 무엇인지({@code RecipientDirectory.resolve}). #100 A·D 합의의 다섯 결과 그대로다.
 *
 * <p>신원·인증 상태·알려진 번호만 나타낸다. 문자를 보내도 되는지(문자 설정·수신 거부·호실 상태)나 혼자 진행할 수 있는지는 정하지 않는다. 그것은
 * notification이 다른 사실과 함께 판단한다. 번호가 없다는 것만으로 수신 거부라고 보지 않는다.
 *
 * <p>번호가 들어가는 결과의 {@code toString}은 번호를 보여 주지 않는다(04 "개인정보 로그").
 */
public sealed interface Recipient
    permits Recipient.VerifiedMember,
        Recipient.UnverifiedMember,
        Recipient.Invitee,
        Recipient.InviteeWithoutPhone,
        Recipient.Unavailable {

  /** 연결된 회원 자리({@code CONNECTED}·{@code SHARING_STOPPED})이고 회원의 인증된 번호가 있음. */
  record VerifiedMember(UUID memberId, String phone) implements Recipient {

    public VerifiedMember {
      Objects.requireNonNull(memberId, "memberId");
      requirePhone(phone);
    }

    @Override
    public String toString() {
      return "VerifiedMember[memberId=" + memberId + ", phone=***]";
    }
  }

  /** 연결된 회원 자리({@code CONNECTED}·{@code SHARING_STOPPED})인데 회원의 번호 인증이 풀림. */
  record UnverifiedMember(UUID memberId) implements Recipient {

    public UnverifiedMember {
      Objects.requireNonNull(memberId, "memberId");
    }
  }

  /** 아직 연결하지 않은 초대 자리({@code INVITED})이고 초대 번호가 있음. */
  record Invitee(String phone) implements Recipient {

    public Invitee {
      requirePhone(phone);
    }

    @Override
    public String toString() {
      return "Invitee[phone=***]";
    }
  }

  /** 초대 자리({@code INVITED})인데 초대 번호가 지워짐(수신 거부 등). 번호가 없다는 것만으로 이유를 짐작하지 않는다. */
  record InviteeWithoutPhone() implements Recipient {}

  /**
   * 통지를 받을 사람이 없음: {@code EMPTY}·번호만 있는 자리({@code NUMBER_ONLY})·{@code REJECTED}·{@code WITHDRAWN}
   * 등.
   */
  record Unavailable() implements Recipient {}

  private static void requirePhone(String phone) {
    Objects.requireNonNull(phone, "phone");
    if (phone.isBlank()) {
      throw new IllegalArgumentException("번호가 비어 있으면 번호 없는 결과를 쓴다");
    }
  }
}
